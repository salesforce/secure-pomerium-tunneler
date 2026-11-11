package com.salesforce.intellij.gateway.connector

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class ClientRelauncherTest {

    private val lifetime = LifetimeDefinition()
    private val relaunches = AtomicInteger()
    private var clientPresent = true
    private val relauncher = ClientRelauncher(lifetime, { clientPresent }, { relaunches.incrementAndGet() }, 50.milliseconds)

    @AfterEach
    fun tearDown() {
        lifetime.terminate()
    }

    @Test
    fun `relaunches once when the client never reconnects`() = runBlocking {
        relauncher.onConnectionOpened()
        relauncher.onConnectionClosed()
        delay(300)
        Assertions.assertEquals(1, relaunches.get())
    }

    @Test
    fun `does not relaunch when the client opens a new connection`() = runBlocking {
        relauncher.onConnectionOpened()
        relauncher.onConnectionClosed()
        delay(25)
        relauncher.onConnectionOpened()
        delay(300)
        Assertions.assertEquals(0, relaunches.get())
    }

    @Test
    fun `a reconnect that drops again relaunches once, not twice`() = runBlocking {
        relauncher.onConnectionOpened()
        relauncher.onConnectionClosed()
        delay(25)
        relauncher.onConnectionOpened()
        relauncher.onConnectionClosed()
        delay(300)
        Assertions.assertEquals(1, relaunches.get())
    }

    @Test
    fun `does not relaunch while another connection is still open`() = runBlocking {
        relauncher.onConnectionOpened()
        relauncher.onConnectionOpened()
        relauncher.onConnectionClosed()
        delay(300)
        Assertions.assertEquals(0, relaunches.get())
    }

    @Test
    fun `does not relaunch when the client is gone`() = runBlocking {
        clientPresent = false
        relauncher.onConnectionOpened()
        relauncher.onConnectionClosed()
        delay(300)
        Assertions.assertEquals(0, relaunches.get())
    }

    @Test
    fun `does not relaunch after the lifetime ends`() = runBlocking {
        relauncher.onConnectionOpened()
        relauncher.onConnectionClosed()
        lifetime.terminate()
        delay(300)
        Assertions.assertEquals(0, relaunches.get())
    }
}

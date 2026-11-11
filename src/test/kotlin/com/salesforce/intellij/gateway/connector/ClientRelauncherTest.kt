package com.salesforce.intellij.gateway.connector

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ClientRelauncherTest {

    private val lifetime = LifetimeDefinition()
    private var relaunches = 0
    private var clientPresent = true

    @AfterEach
    fun tearDown() {
        lifetime.terminate()
    }

    private fun TestScope.relauncherWithDroppedConnection() =
        ClientRelauncher(lifetime, { clientPresent }, { relaunches++ }, 30.seconds, StandardTestDispatcher(testScheduler))
            .apply {
                onConnectionOpened()
                onConnectionClosed()
            }

    @Test
    fun `relaunches when the client does not reconnect`() = runTest {
        relauncherWithDroppedConnection()
        advanceTimeBy(31.seconds)
        Assertions.assertEquals(1, relaunches)
    }

    @Test
    fun `does not relaunch when the client reconnects`() = runTest {
        val relauncher = relauncherWithDroppedConnection()
        advanceTimeBy(10.seconds)
        relauncher.onConnectionOpened()
        advanceTimeBy(31.seconds)
        Assertions.assertEquals(0, relaunches)
    }

    @Test
    fun `does not relaunch when the client is gone`() = runTest {
        clientPresent = false
        relauncherWithDroppedConnection()
        advanceTimeBy(31.seconds)
        Assertions.assertEquals(0, relaunches)
    }
}

package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.junit5.TestApplication
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isAlive
import com.jetbrains.rd.util.reactive.ISource
import com.salesforce.pomerium.MockPomerium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestApplication
class PomeriumBasedGatewayConnectorProviderTest {

    @Test
    fun `test connector end to end`() = runTest(timeout = 20.seconds) {
        val port = mockPomerium.startMockPomerium()
        Registry.get(POMERIUM_PORT_KEY).setValue(port)

        val fragments = mapOf(PomeriumBasedGatewayConnectionProvider.PARAM_ROUTE to "tcp+http://${mockPomerium.route}",
                        PomeriumBasedGatewayConnectionProvider.PARAM_CONNECTION_KEY to "tcp-direct://0.0.0.0:5990#p=IU&fp=71B1&cb=262.11879.100&jb=25.0.4.1b508.41")

        var socketConnected = false
        val provider = PomeriumBasedGatewayConnectionProvider { lifetime, initialLink, remoteIdentity ->
            Assertions.assertEquals("tcp-direct", initialLink.scheme)
            Assertions.assertEquals("p=IU&cb=262.11879.100&jb=25.0.4.1b508.41", initialLink.rawFragment)
            Socket(initialLink.host, initialLink.port).use {
                val echo = "echo"
                it.getOutputStream().write(echo.encodeToByteArray())
                Assertions.assertEquals(echo, String(it.getInputStream().readNBytes(4)))
                socketConnected = true
            }
            mock<ThinClientHandle> {
                on { clientClosed } doReturn object: ISource<Unit> {
                    override fun advise(lifetime: Lifetime, handler: (Unit) -> Unit) {

                    }
                }
                on { clientFailedToOpenProject } doReturn object: ISource<Int> {
                    override fun advise(lifetime: Lifetime, handler: (Int) -> Unit) {

                    }
                }
                on { onClientPresenceChanged } doReturn object: ISource<Unit> {
                    override fun advise(lifetime: Lifetime, handler: (Unit) -> Unit) {

                    }
                }

            }
        }
        
        provider.connect(fragments, ConnectionRequestor.Local)

        Assertions.assertEquals(1, mockPomerium.requestCount)
        Assertions.assertTrue(socketConnected)

        // Ensure proper cleanup
        provider.dispose()
    }

    @Test
    fun `test client close ends the connection`() = runTest(timeout = 20.seconds) {
        Assertions.assertFalse(connectAndCloseClient(restartNeeded = false))
    }

    @Test
    fun `test client close keeps the connection when the client is restarting`() = runTest(timeout = 20.seconds) {
        Assertions.assertTrue(connectAndCloseClient(restartNeeded = true))
    }

    @Test
    fun `test stuck client is relaunched and its close does not end the connection`() = runTest(timeout = 20.seconds) {
        val handles = CopyOnWriteArrayList<ThinClientHandle>()
        val onClose = CopyOnWriteArrayList<() -> Unit>()
        val provider = PomeriumBasedGatewayConnectionProvider(relaunchWait = 100.milliseconds) { _, initialLink, _ ->
            if (handles.isEmpty()) {
                // The first client connects through the tunnel, then loses the connection (like on a backend restart)
                Socket(initialLink.host, initialLink.port).use {
                    it.getOutputStream().write("echo".encodeToByteArray())
                    it.getInputStream().readNBytes(4)
                }
            }
            fakeHandle(onClose).also { handles.add(it) }
        }
        try {
            val connection = provider.connect(connectionParameters(), ConnectionRequestor.Local)!!
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) { while (handles.size < 2) delay(20) }
            }
            verify(handles[0]).close()
            onClose[0]()
            Assertions.assertTrue(connection.lifetime.isAlive)
            onClose[1]()
            Assertions.assertFalse(connection.lifetime.isAlive)
        } finally {
            provider.dispose()
        }
    }

    // Connects, fires clientClosed on the handle, and returns whether the connection lifetime is still alive.
    private suspend fun connectAndCloseClient(restartNeeded: Boolean): Boolean {
        val onClose = CopyOnWriteArrayList<() -> Unit>()
        val provider = PomeriumBasedGatewayConnectionProvider { _, _, _ -> fakeHandle(onClose, restartNeeded) }
        try {
            val connection = provider.connect(connectionParameters(), ConnectionRequestor.Local)!!
            onClose.single()()
            return connection.lifetime.isAlive
        } finally {
            provider.dispose()
        }
    }

    private suspend fun connectionParameters(): Map<String, String> {
        Registry.get(POMERIUM_PORT_KEY).setValue(mockPomerium.startMockPomerium())
        return mapOf(PomeriumBasedGatewayConnectionProvider.PARAM_ROUTE to "tcp+http://${mockPomerium.route}",
            PomeriumBasedGatewayConnectionProvider.PARAM_CONNECTION_KEY to "tcp-direct://0.0.0.0:5990#p=IU")
    }

    // A present client whose clientClosed handlers are collected into onClose
    private fun fakeHandle(onClose: MutableList<() -> Unit>, restartNeeded: Boolean = false) = mock<ThinClientHandle> {
        on { clientPresent } doReturn true
        on { this.restartNeeded } doReturn restartNeeded
        on { clientClosed } doReturn object : ISource<Unit> {
            override fun advise(lifetime: Lifetime, handler: (Unit) -> Unit) {
                onClose.add { handler(Unit) }
            }
        }
        on { clientFailedToOpenProject } doReturn ignoredSource()
        on { onClientPresenceChanged } doReturn ignoredSource()
    }

    private fun <T> ignoredSource() = object : ISource<T> {
        override fun advise(lifetime: Lifetime, handler: (T) -> Unit) {}
    }

    companion object {
        @JvmStatic
        @RegisterExtension
        private val mockPomerium = MockPomerium()
    }
}
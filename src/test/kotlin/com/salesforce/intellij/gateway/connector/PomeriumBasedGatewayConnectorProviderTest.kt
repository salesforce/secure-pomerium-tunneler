package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.junit5.TestApplication
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.ISource
import com.salesforce.pomerium.MockPomerium
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.net.Socket
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

    companion object {
        @JvmStatic
        @RegisterExtension
        private val mockPomerium = MockPomerium()
    }
}
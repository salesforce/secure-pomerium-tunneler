package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.LightPlatformTestCase
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.ISource
import com.salesforce.pomerium.MockPomerium
import org.junit.Assert
import org.junit.Rule
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

class PomeriumBasedGatewayConnectorProviderTest: LightPlatformTestCase() {

    @Rule
    @JvmField
    val mockPomerium = MockPomerium()
    fun `test connector end to end`() = kotlinx.coroutines.test.runTest(timeout = 20.seconds) {
        val port = mockPomerium.startMockPomerium()
        Registry.get(POMERIUM_PORT_KEY).setValue(port)

        val fragments = mapOf(PomeriumBasedGatewayConnectionProvider.PARAM_ROUTE to "tcp+http://${mockPomerium.route}",
                        PomeriumBasedGatewayConnectionProvider.PARAM_CONNECTION_KEY to "tcp://0.0.0.0:5990#jt=stub&p=IU&fp=stub&cb=231.9161.38&jb=17.0.7b829.16")

        var socketConnected = false
        PomeriumBasedGatewayConnectionProvider { lifetime, initialLink, remoteIdentity ->
            Socket(initialLink.host, initialLink.port).use {
                val echo = "echo"
                it.getOutputStream().write(echo.encodeToByteArray())
                Assert.assertEquals(echo, String(it.getInputStream().readNBytes(4)))
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
        }.connect(fragments, ConnectionRequestor.Local)

        Assert.assertEquals(1, mockPomerium.requestCount)
        Assert.assertTrue(socketConnected)
    }
}
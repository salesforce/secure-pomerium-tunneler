package com.salesforce.intellij.gateway.connector

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.URI

class ClientLinkTest {

    @Test
    fun `tcp-direct client link is the same across backend restarts`() {
        val beforeRestart = PomeriumBasedGatewayConnectionProvider.clientLink(4242,
            "tcp-direct://0.0.0.0:5990#p=IU&cb=262.11879.100&newUi=true&jb=25.0.4.1b508.41&fp=DECF")
        val afterRestart = PomeriumBasedGatewayConnectionProvider.clientLink(4242,
            "tcp-direct://0.0.0.0:5990#p=IU&cb=262.11879.100&newUi=true&jb=25.0.4.1b508.41&fp=71B1")

        Assertions.assertEquals(URI("tcp-direct://127.0.0.1:4242#p=IU&cb=262.11879.100&newUi=true&jb=25.0.4.1b508.41"), beforeRestart)
        Assertions.assertEquals(beforeRestart, afterRestart)
    }

    @Test
    fun `tcp client link keeps all parameters`() {
        Assertions.assertEquals(URI("tcp://127.0.0.1:4242#jt=aaa&p=IU&fp=0C30&cb=262.10968.63&newUi=true&jb=25.0.4b508.27"),
            PomeriumBasedGatewayConnectionProvider.clientLink(4242,
                "tcp://0.0.0.0:5990#jt=aaa&p=IU&fp=0C30&cb=262.10968.63&newUi=true&jb=25.0.4b508.27"))
    }
}

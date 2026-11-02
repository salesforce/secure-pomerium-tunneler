package com.salesforce.intellij.gateway.connector

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.URI

class LocalClientLinkTest {

    @Test
    fun `tcp join link keeps the join token and the fingerprint`() {
        val link = PomeriumBasedGatewayConnectionProvider.localClientLink(
            4242, "tcp://0.0.0.0:5990#jt=stub&p=IU&fp=stub&cb=231.9161.38&jb=17.0.7b829.16")

        Assertions.assertEquals(URI("tcp://127.0.0.1:4242#jt=stub&p=IU&fp=stub&cb=231.9161.38&jb=17.0.7b829.16"), link)
    }

    @Test
    fun `tcp-direct join link keeps the tcp-direct scheme`() {
        val link = PomeriumBasedGatewayConnectionProvider.localClientLink(
            4242, "tcp-direct://0.0.0.0:5990#p=IU&cb=262.11879.100&newUi=true&jb=25.0.4.1b508.41")

        Assertions.assertEquals(URI("tcp-direct://127.0.0.1:4242#p=IU&cb=262.11879.100&newUi=true&jb=25.0.4.1b508.41"), link)
    }

    @Test
    fun `encoded characters in the fragment stay encoded`() {
        val link = PomeriumBasedGatewayConnectionProvider.localClientLink(
            4242, "tcp://0.0.0.0:5990#jt=a%20b&cb=262.11879.100&jb=25.0.4.1b508.41")

        Assertions.assertEquals("jt=a%20b&cb=262.11879.100&jb=25.0.4.1b508.41", link.rawFragment)
    }
}

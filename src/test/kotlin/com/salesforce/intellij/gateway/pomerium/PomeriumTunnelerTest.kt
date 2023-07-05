package com.salesforce.intellij.gateway.pomerium

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.random.Random

class PomeriumTunnelerTest {
    @JvmField
    @Rule
    val mockPomerium = MockPomerium()
    val lifetime = LifetimeDefinition()

    @After
    fun teardown() {
        lifetime.terminate()
    }

    @Test
    fun `test end to end tunnler`() = runTest {
        val authProvider = mock<PomeriumAuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred(mockPomerium.token)
        }
        val pomeriumTunneler = PomeriumTunneler(authProvider, 100, false)
        val mockPomeriumPort = mockPomerium.startMockPomerium()

        val uri = URI("tcp://${mockPomerium.route}")
        val port = pomeriumTunneler.startTunnel(uri, lifetime, pomeriumPort = mockPomeriumPort)
        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            val line = ips.readNBytes(1024)
            Assert.assertEquals(0, ips.available())
            Assert.assertArrayEquals(testEchoMessage, line)
        }
    }

    @Test
    fun `test tunneler with reconnect`() = runTest {
        val authProvider = mock<PomeriumAuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred(mockPomerium.token)
        }
        val pomeriumTunneler = PomeriumTunneler(authProvider, 100, false)
        val mockPomeriumPort = mockPomerium.startMockPomerium()

        val uri = URI("tcp://${mockPomerium.route}")
        val port = pomeriumTunneler.startTunnel(uri, lifetime, pomeriumPort = mockPomeriumPort)
        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            ips.readNBytes(102)
        }
        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            val line = ips.readNBytes(1024)
            Assert.assertEquals(0, ips.available())
            Assert.assertArrayEquals(testEchoMessage, line)
        }
    }

    @Test
    fun `test tunneler with pomerium disconnect`() = runTest {
        val authProvider = mock<PomeriumAuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred(mockPomerium.token)
        }
        val pomeriumTunneler = PomeriumTunneler(authProvider, 500, false)
        val mockPomeriumPort = mockPomerium.startMockPomerium()

        val uri = URI("tcp://${mockPomerium.route}")
        val port = pomeriumTunneler.startTunnel(uri, lifetime, pomeriumPort = mockPomeriumPort)
        try {
            Socket("localhost", port).use {
                it.soTimeout = 500
                val testEchoMessage = Random.nextBytes(1024)
                it.getOutputStream().write(testEchoMessage)
                val ips = it.getInputStream()
                var line = ips.readNBytes(1024)
                Assert.assertEquals(0, ips.available())
                Assert.assertArrayEquals(testEchoMessage, line)

                mockPomerium.killConnection()

                val testEchoMessage2 = Random.nextBytes(1024)
                it.getOutputStream().write(testEchoMessage2)
                ips.readNBytes(1024)
            }
        } catch (e: SocketTimeoutException) {
            //expected
        }
        Assert.assertEquals(1, mockPomerium.requestCount)
        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            val line = ips.readNBytes(1024)
            Assert.assertEquals(0, ips.available())
            Assert.assertArrayEquals(testEchoMessage, line)
        }
        Assert.assertEquals(2, mockPomerium.requestCount)
    }


    @Test
    fun `test tunneler with auth blocked waiting`() = runTest {
        var authCount = 0
        val job = async(Dispatchers.Default) {
            while (authCount <= 1) {
                delay(10)
            }
            return@async mockPomerium.token
        }
        val authProvider = mock<PomeriumAuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred("") doSuspendableAnswer {
                ++authCount
                job
            }
        }
        val pomeriumTunneler = PomeriumTunneler(authProvider, 100, false)
        val mockPomeriumPort = mockPomerium.startMockPomerium()

        val uri = URI("tcp://${mockPomerium.route}")
        val port = pomeriumTunneler.startTunnel(uri, lifetime, pomeriumPort = mockPomeriumPort)
        try {
            Socket("localhost", port).use {
                it.soTimeout = 100
                val testEchoMessage = Random.nextBytes(1024)
                it.getOutputStream().write(testEchoMessage)
                val ips = it.getInputStream()
                var line = ips.readNBytes(1024)
                Assert.assertEquals(0, ips.available())
                Assert.assertArrayEquals(testEchoMessage, line)
            }
        } catch (e: SocketTimeoutException) {
            //Expected
        }

        Thread.sleep(100) //Ensure we wait the timeout

        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            var line = ips.readNBytes(1024)
            Assert.assertEquals(0, ips.available())
            Assert.assertArrayEquals(testEchoMessage, line)
        }

        //The WithTimeout should prevent
        Assert.assertEquals(1, mockPomerium.requestCount)
    }
}
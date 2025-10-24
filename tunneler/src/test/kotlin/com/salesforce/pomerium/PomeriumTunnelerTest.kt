package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.random.Random

class PomeriumTunnelerTest {
    @JvmField
    @Rule
    val mockPomerium = MockPomerium()
    val lifetime = LifetimeDefinition()

    @AfterEach
    fun teardown() {
        lifetime.terminate()
    }

    @Test
    fun `test end to end tunnler`() = runTest {
        val authProvider = mock<AuthProvider> {
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
            Assertions.assertEquals(0, ips.available())
            Assertions.assertArrayEquals(testEchoMessage, line)
        }
    }

    @Test
    fun `test tunneler with reconnect`() = runTest {
        val authProvider = mock<AuthProvider> {
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
            Assertions.assertEquals(0, ips.available())
            Assertions.assertArrayEquals(testEchoMessage, line)
        }
    }

    @Test
    fun `test tunneler with pomerium disconnect`() = runTest {
        val authProvider = mock<AuthProvider> {
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
                val line = ips.readNBytes(1024)
                Assertions.assertEquals(0, ips.available())
                Assertions.assertArrayEquals(testEchoMessage, line)

                mockPomerium.killConnection()

                val testEchoMessage2 = Random.nextBytes(1024)
                it.getOutputStream().write(testEchoMessage2)
                ips.readNBytes(1024)
            }
        } catch (e: SocketTimeoutException) {
            //expected
        }
        Assertions.assertEquals(1, mockPomerium.requestCount)
        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            val line = ips.readNBytes(1024)
            Assertions.assertEquals(0, ips.available())
            Assertions.assertArrayEquals(testEchoMessage, line)
        }
        Assertions.assertEquals(2, mockPomerium.requestCount)
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
        val authProvider = mock<AuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred("") doSuspendableAnswer {
                ++authCount
                job
            }
        }
        val pomeriumTunneler = PomeriumTunneler(authProvider, 200, false)
        val mockPomeriumPort = mockPomerium.startMockPomerium()

        val uri = URI("tcp://${mockPomerium.route}")
        val port = pomeriumTunneler.startTunnel(uri, lifetime, pomeriumPort = mockPomeriumPort)
        try {
            Socket("localhost", port).use {
                it.soTimeout = 100
                val testEchoMessage = Random.nextBytes(1024)
                it.getOutputStream().write(testEchoMessage)
                val ips = it.getInputStream()
                val line = ips.readNBytes(1024)
                Assertions.assertEquals(0, ips.available())
                Assertions.assertArrayEquals(testEchoMessage, line)
            }
        } catch (e: SocketTimeoutException) {
            //Expected
        }

        Thread.sleep(100) //Ensure we wait the timeout

        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            val line = ips.readNBytes(1024)
            Assertions.assertEquals(0, ips.available())
            Assertions.assertArrayEquals(testEchoMessage, line)
        }

        //The WithTimeout should prevent
        Assertions.assertEquals(1, mockPomerium.requestCount)
    }

    @Test
    fun `test tunneler with auth blocked waiting closes connection on timeout`() = runTest {
        var authCount = 0

        val authProvider = mock<AuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred("") doSuspendableAnswer {
                async {
                    delay(200)
                    return@async ""
                }
            }
        }
        val pomeriumTunneler = PomeriumTunneler(authProvider, 100, false)
        val mockPomeriumPort = mockPomerium.startMockPomerium()

        val uri = URI("tcp://${mockPomerium.route}")
        val port = pomeriumTunneler.startTunnel(uri, lifetime, pomeriumPort = mockPomeriumPort)
        try {
            Socket("localhost", port).use {
                it.soTimeout = 200
                val testEchoMessage = Random.nextBytes(1024)
                it.getOutputStream().write(testEchoMessage)
                val ips = it.getInputStream()
                val line = ips.readNBytes(1024)
                Assertions.assertEquals(0, ips.available())
                Assertions.assertArrayEquals(testEchoMessage, line)
            }
        } catch (e: SocketException) {
            Assertions.assertTrue(e.message?.contains("Connection reset") ?: false)
            //Expected
        }
    }

    @Test
    fun `test tunnler with lifetime termination`() = runTest {
        val authProvider = mock<AuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred(mockPomerium.token)
        }
        val pomeriumTunneler = PomeriumTunneler(authProvider, 100, false)
        val mockPomeriumPort = mockPomerium.startMockPomerium()

        val uri = URI("tcp://${mockPomerium.route}")
        val childLifetime = lifetime.createNested()
        val port = pomeriumTunneler.startTunnel(uri, childLifetime, pomeriumPort = mockPomeriumPort)
        Socket("localhost", port).use {
            val testEchoMessage = Random.nextBytes(1024)
            it.getOutputStream().write(testEchoMessage)
            val ips = it.getInputStream()
            val line = ips.readNBytes(1024)
            Assertions.assertEquals(0, ips.available())
            Assertions.assertArrayEquals(testEchoMessage, line)
        }

        childLifetime.terminate()
        Thread.sleep(100) //Need to wait for the cancellation to propagate
        Assertions.assertFalse(pomeriumTunneler.isTunneling())
    }

    @Test
    fun `test multiple tunnels can be created successfully`() = runTest {
        val authProvider = mock<AuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred(mockPomerium.token)
        }
        val tunneler = PomeriumTunneler(authProvider, 100, false)

        try {
            val tunnelCount = 10
            val lifetimes = mutableListOf<LifetimeDefinition>()
            val ports = mutableListOf<Int>()

            repeat(tunnelCount) { i ->
                val lifetimeDef = lifetime.createNested()
                lifetimes.add(lifetimeDef)

                val port = tunneler.startTunnel(
                    route = URI("http://test-route-$i.example.com"),
                    lifetime = lifetimeDef
                )
                ports.add(port)
            }

            Assertions.assertEquals(tunnelCount, ports.size)
            Assertions.assertTrue(ports.all { it > 0 })
            Assertions.assertEquals(tunnelCount, ports.toSet().size)
            Assertions.assertTrue(ports.all { it in 1024..65535 })
            Assertions.assertTrue(tunneler.isTunneling())

            lifetimes.forEach { it.terminate() }
            delay(100)

        } finally {
            tunneler.close()
        }
    }

    @Test
    fun `test proper resource cleanup`() = runTest {
        val authProvider = mock<AuthProvider> {
            onBlocking { getAuth(any(), any()) } doReturn CompletableDeferred(mockPomerium.token)
        }
        val tunneler = PomeriumTunneler(authProvider, 100, false)

        try {
            val lifetimes = mutableListOf<LifetimeDefinition>()
            repeat(5) { i ->
                val lifetimeDef = lifetime.createNested()
                lifetimes.add(lifetimeDef)
                tunneler.startTunnel(
                    route = URI("http://test-route-$i.example.com"),
                    lifetime = lifetimeDef
                )
            }

            Assertions.assertTrue(tunneler.isTunneling())

            lifetimes.forEach { it.terminate() }
            delay(100)

            Assertions.assertFalse(tunneler.isTunneling())

            tunneler.close()
            Assertions.assertFalse(tunneler.isTunneling())

        } finally {
            tunneler.close()
        }
    }
}

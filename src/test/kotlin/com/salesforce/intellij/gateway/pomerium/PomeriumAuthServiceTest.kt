package com.salesforce.intellij.gateway.pomerium

import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset

class PomeriumAuthServiceTest: BasePlatformTestCase() {
    private val lifetime = LifetimeDefinition()
    @Test
    fun `test auth flow end to end`() = runTest {
        val testAuthEndpoint = "http://example.com"
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(testAuthEndpoint))
        server.start()

        val authService = PomeriumAuthService(server.port)

        val route = URI("http://localhost:${server.port}")
        val authJob = authService.getAuth(route, lifetime)
        authJob.start()

        val request = server.takeRequest()
        val query = request.requestUrl!!.toUrl().query
        val parts = query.split("=")
        Assert.assertEquals(PomeriumAuthService.POMERIUM_LOGIN_REDIRECT_PARAM, parts[0])
        val localServer = URLDecoder.decode(parts[1], Charset.defaultCharset())

        val testJwt = "someRansomTestString"
        val jwtRequest = Request.Builder()
            .get()
            .url(localServer + "?${PomeriumAuthService.POMERIUM_JWT_QUERY_PARAM}=${testJwt}")
            .build()
        OkHttpClient().newCall(jwtRequest).execute().use {
            Assert.assertTrue(it.isSuccessful)
        }
        Assert.assertEquals(testJwt, authJob.await())
        Thread.sleep(100) //Allow the coroutine which sets the password to update. Probably should make this better
        Assert.assertEquals(testJwt, PasswordSafe.instance.getPassword(PomeriumAuthService.getCredString(URI(testAuthEndpoint))))
    }

    @Test
    fun `test auth job is cached`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.start()

        val authService = PomeriumAuthService(server.port)

        val route = URI("http://localhost:${server.port}")
        Assert.assertEquals(authService.getAuth(route, lifetime), authService.getAuth(route, lifetime))
    }

    @Test
    fun `test auth job is not cached for different auth endpoints`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://other.example.com"))
        server.start()

        val authService = PomeriumAuthService(server.port)

        val route = URI("http://localhost:${server.port}")
        Assert.assertNotEquals(authService.getAuth(route, lifetime), authService.getAuth(route, lifetime))
    }

    @Test
    fun `test auth job is invalidated`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.start()

        val authService = PomeriumAuthService(server.port)

        val route = URI("http://localhost:${server.port}")

        val job = authService.getAuth(route, lifetime)
        authService.invalidate(route)
        Assert.assertNotEquals(job, authService.getAuth(route, lifetime))
    }

    @Test
    fun `test auth invalidation with cold cache`() = runTest {
        val testAuth = "testAuthToken"
        val authLink = URI.create("http://auth.example.com")
        val credAttr = PomeriumAuthService.getCredString(authLink)
        PasswordSafe.instance.setPassword(credAttr, testAuth)

        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(authLink.toString()))
        server.start()

        val route = URI("http://localhost:${server.port}")
        val authService = PomeriumAuthService(server.port)
        authService.invalidate(route)

        Assert.assertEquals(1, server.requestCount)
        Assert.assertNull(PasswordSafe.instance.getPassword(credAttr))
    }
}
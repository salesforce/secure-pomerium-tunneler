package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset

class PomeriumAuthProviderTest {

    class InMemoryCredStore : CredentialStore {
        private val cache = HashMap<CredentialKey, String>()
        override fun getToken(key: CredentialKey) = cache[key]

        override fun setToken(key: CredentialKey, jwt: String) {
            cache[key] = jwt
        }

        override fun clearToken(key: CredentialKey) {
            cache.remove(key)
        }

    }

    object NoOpAuthLinkHandler: AuthLinkHandler {
        override fun handleAuthLink(getLink: () -> URI, jobLifetime: LifetimeDefinition, newRoute: Boolean) {
            //Nothing
        }

    }

    private val lifetime = Lifetime.Eternal.createNested()

    @Test
    fun `test auth flow end to end`() = runTest {
        val testAuthEndpoint = "http://example.com"
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(testAuthEndpoint))
        server.start()

        val credStore = InMemoryCredStore()
        val authService = PomeriumAuthProvider(credStore, NoOpAuthLinkHandler, server.port)

        val route = URI("http://localhost:${server.port}")
        val authJob = authService.getAuth(route, lifetime)
        authJob.start()

        val request = server.takeRequest()
        val query = request.requestUrl!!.toUrl().query
        val parts = query.split("=")
        Assertions.assertEquals(PomeriumAuthProvider.POMERIUM_LOGIN_REDIRECT_PARAM, parts[0])
        val localServer = URLDecoder.decode(parts[1], Charset.defaultCharset())

        val testJwt = "someRansomTestString"
        val jwtRequest = Request.Builder()
            .get()
            .url(localServer + "?${PomeriumAuthProvider.POMERIUM_JWT_QUERY_PARAM}=${testJwt}")
            .build()
        OkHttpClient().newCall(jwtRequest).execute().use {
            Assertions.assertTrue(it.isSuccessful)
        }
        Assertions.assertEquals(testJwt, authJob.await())
        Thread.sleep(100) //Allow the coroutine which sets the password to update. Probably should make this better
        Assertions.assertEquals(
            testJwt,
            credStore.getToken(
                PomeriumAuthProvider.getCredString(
                    URI(testAuthEndpoint)
                )
            )
        )
    }

    @Test
    fun `test auth job is cached`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.start()

        val authService = PomeriumAuthProvider(InMemoryCredStore(), NoOpAuthLinkHandler, server.port)

        val route = URI("http://localhost:${server.port}")
        Assertions.assertEquals(authService.getAuth(route, lifetime), authService.getAuth(route, lifetime))
    }

    @Test
    fun `test auth job is cached using auth endpoint`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.start()

        val authService = PomeriumAuthProvider(InMemoryCredStore(), NoOpAuthLinkHandler, server.port)

        Assertions.assertEquals(
            authService.getAuth(URI("http://localhost:${server.port}"), lifetime),
            authService.getAuth(URI("http://localhost:${server.port}/differentPath"), lifetime)
        )
    }

    @Test
    fun `test auth job is not cached for different auth endpoints`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://other.example.com"))
        server.start()

        val authService = PomeriumAuthProvider(InMemoryCredStore(), NoOpAuthLinkHandler, server.port)

        Assertions.assertNotEquals(
            authService.getAuth(URI("http://localhost:${server.port}"), lifetime),
            authService.getAuth(URI("http://localhost:${server.port}/differentPath"), lifetime)
        )
    }

    @Test
    fun `test auth job is invalidated`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.start()

        val authService = PomeriumAuthProvider(InMemoryCredStore(), NoOpAuthLinkHandler, server.port)

        val route = URI("http://localhost:${server.port}")

        val job = authService.getAuth(route, lifetime)
        authService.invalidate(route)
        Assertions.assertNotEquals(job, authService.getAuth(route, lifetime))
    }

    @Test
    fun `test auth invalidation with cold cache`() = runTest {
        val testAuth = "testAuthToken"
        val authLink = URI.create("http://auth.example.com")
        val credAttr = PomeriumAuthProvider.getCredString(authLink)
        val credStore = InMemoryCredStore()
        credStore.setToken(credAttr, testAuth)

        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(authLink.toString()))
        server.start()

        val route = URI("http://localhost:${server.port}")
        val authService = PomeriumAuthProvider(credStore, NoOpAuthLinkHandler, server.port)
        authService.invalidate(route)

        Assertions.assertEquals(1, server.requestCount)
        Assertions.assertNull(credStore.getToken(credAttr))
    }


    @Test
    fun `test job stays running with original lifetime terminating`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.start()
        val route = URI("http://localhost:${server.port}")

        val authService = PomeriumAuthProvider(InMemoryCredStore(), NoOpAuthLinkHandler, server.port)
        val lifetime1 = lifetime.createNested()
        val job = authService.getAuth(route, lifetime1)

        val lifetime2 = lifetime.createNested()
        authService.getAuth(route, lifetime2)

        lifetime1.terminate()
        Assertions.assertTrue(job.isActive)

        lifetime2.terminate()
        Assertions.assertFalse(job.isActive)
    }


    @Test
    fun `test auth link handler values`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com/1"))
        server.enqueue(MockResponse().setBody("http://example.com/2"))
        server.start()
        val route = URI("http://localhost:${server.port}")

        var handlerCalled = false
        val handler = object : AuthLinkHandler {
            override fun handleAuthLink(getLink: () -> URI, jobLifetime: LifetimeDefinition, newRoute: Boolean) {
                //A bit weird, but the first call does not get passed. An optimization we could make
                //would be to return the initial auth link with some expiration
                Assertions.assertEquals("http://example.com/1", getLink().toString())
                Assertions.assertEquals("http://example.com/2", getLink().toString())
                Assertions.assertTrue(newRoute)
                handlerCalled = true
            }

        }
        val authService = PomeriumAuthProvider(InMemoryCredStore(), handler, server.port)
        authService.getAuth(route, lifetime)

        Assertions.assertTrue(handlerCalled)
    }

    @Test
    fun `test auth link handler that terminates job`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("http://example.com"))
        server.enqueue(MockResponse().setBody("http://example.com/1"))
        server.start()
        val route = URI("http://localhost:${server.port}")

        val handler = object : AuthLinkHandler {
            override fun handleAuthLink(getLink: () -> URI, jobLifetime: LifetimeDefinition, newRoute: Boolean) {
                jobLifetime.terminate()
            }

        }
        val authService = PomeriumAuthProvider(InMemoryCredStore(), handler, server.port)
        val job = authService.getAuth(route, lifetime)

        Assertions.assertTrue(job.isCancelled)
    }

    @Test
    fun `test default auth redirect response`() = runTest {
        val testAuthEndpoint = "http://example.com"
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(testAuthEndpoint))
        server.start()

        val credStore = InMemoryCredStore()
        val authService = PomeriumAuthProvider(credStore, NoOpAuthLinkHandler, server.port,
            DefaultAuthRedirectResponseHandler())

        val route = URI("http://localhost:${server.port}")
        val authJob = authService.getAuth(route, lifetime)
        authJob.start()

        val request = server.takeRequest()
        val query = request.requestUrl!!.toUrl().query
        val parts = query.split("=")
        Assertions.assertEquals(PomeriumAuthProvider.POMERIUM_LOGIN_REDIRECT_PARAM, parts[0])
        val localServer = URLDecoder.decode(parts[1], Charset.defaultCharset())

        val badRequest = Request.Builder()
            .get()
            .url(localServer)
            .build()

        OkHttpClient().newCall(badRequest).execute().use {
            Assertions.assertFalse(it.isSuccessful)
            Assertions.assertEquals(DefaultAuthRedirectResponseHandler.RESPONSE_FAILURE, it.body!!.string())
            Assertions.assertEquals(400, it.code)
        }

        val testJwt = "someRandomTestString"
        val goodRequest = Request.Builder()
            .get()
            .url(localServer + "?${PomeriumAuthProvider.POMERIUM_JWT_QUERY_PARAM}=${testJwt}")
            .build()
        OkHttpClient().newCall(goodRequest).execute().use {
            Assertions.assertTrue(it.isSuccessful)
            Assertions.assertEquals(200, it.code)
            Assertions.assertEquals(DefaultAuthRedirectResponseHandler.RESPONSE, it.body!!.string())
        }
    }
}
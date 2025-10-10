package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.threading.coroutines.async
import com.jetbrains.rd.util.threading.coroutines.synchronizeWith
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.hc.core5.net.URIBuilder
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Service for getting authentication for pomerium controlled routes.
 * Handles caching and reuse by using the pomerium authentication host as a key
 * When a new token is needed, this code will perform a device flow to obtain and cache the token from Pomerium
 */
class PomeriumAuthProvider (
    private val credentialStore: CredentialStore,
    private val linkHandler: AuthLinkHandler = OpenBrowserAuthLinkHandler(),
    private val pomeriumPort: Int = 443,
    sslSocketFactory: SSLSocketFactory? = null,
    trustManager: X509TrustManager? = null
) : AuthProvider {

    private val credKeyToMutexMap = ConcurrentHashMap<CredentialKey, Mutex>()
    private val credKeyToAuthJobMap = HashMap<CredentialKey, Deferred<String>>()
    private val routeToCredKeyMap = HashMap<URI, CredentialKey>()
    private val lifetimesRequestingToken = HashMap<CredentialKey, MutableSet<Lifetime>>()
    private val existingRoutes = HashSet<URI>()
    
    // Shared callback server to prevent Netty EventLoopGroup proliferation
    private val sharedCallbackServer = SharedCallbackServer()

    private val client = HttpClient(OkHttp) {
        if (sslSocketFactory != null && trustManager != null) {
            engine {
                config {
                    sslSocketFactory(sslSocketFactory, trustManager)
                }
            }
        }
    }

    override suspend fun getAuth(route: URI, lifetime: Lifetime): Deferred<String> =
        withContext(Dispatchers.Default) {
            //Check for existing job. Note, this is not guaranteed to be thread safe, but it does not require a network call.
            //There is another, thread-safe check below.
            routeToCredKeyMap[route]?.let {
                credKeyToMutexMap.computeIfAbsent(it) { Mutex() }.withLock {
                    credentialStore.getToken(it)?.let { auth ->
                        return@withContext CompletableDeferred(auth)
                    }
                    credKeyToAuthJobMap[it]?.let { job ->
                        LOG.debug("Existing auth job found in cache, reusing job")
                        lifetimesRequestingToken[it]!!.add(lifetime)
                        lifetime.onTermination {
                            onLifetimeTermination(lifetime, it, job)
                        }
                        return@withContext job
                    }
                }
            }

            val jobLifetime = Lifetime.Eternal.createNested()

            val serverPort = sharedCallbackServer.getPort()
            LOG.info("Using shared HTTP server on port $serverPort for pomerium auth token callback")

            val state = java.util.UUID.randomUUID().toString()
            val authLink = getAuthLink(route, pomeriumPort, serverPort, state)
            val credString = getCredString(authLink)
            return@withContext credKeyToMutexMap.computeIfAbsent(credString) { Mutex() }.withLock {
                credentialStore.getToken(credString)?.let { auth ->
                    return@withLock CompletableDeferred(auth)
                }
                credKeyToAuthJobMap[credString]?.let {
                    LOG.debug("Existing auth job found, reusing job")
                    lifetimesRequestingToken[credString]!!.add(lifetime)
                    lifetime.onTermination {
                        onLifetimeTermination(lifetime, credString, it)
                    }
                    return@withLock it
                }
                routeToCredKeyMap[route] = credString
                val isNewRoute = existingRoutes.add(route)
                val getToken = jobLifetime.async(Dispatchers.Default) {
                    try {
                        val auth = sharedCallbackServer.getToken(state)
                        LOG.info("Successfully acquired Pomerium authentication")
                        credentialStore.setToken(credString, auth)
                        return@async auth
                    } finally {
                        withContext(NonCancellable) {
                            credKeyToMutexMap[credString]!!.withLock {
                                credKeyToAuthJobMap.remove(credString)
                            }
                        }
                    }
                }
                jobLifetime.synchronizeWith(getToken)
                credKeyToAuthJobMap[credString] = getToken
                lifetimesRequestingToken.computeIfAbsent(credString) { HashSet() }.add(lifetime)
                lifetime.onTermination {
                    onLifetimeTermination(lifetime, credString, getToken)
                }

                val authLink = getAuthLink(route, pomeriumPort, serverPort, state)
                linkHandler.handleAuthLink({
                    runBlocking {
                        authLink
                    }
                }, jobLifetime, isNewRoute)

                return@withLock getToken
            }
        }

    override suspend fun invalidate(route: URI) {
        (routeToCredKeyMap.remove(route) ?: run {
            //Port and state do not matter in this case
            val link = getAuthLink(route, pomeriumPort, 8080, "dummy")
            getCredString(link)
        }).also {
            credKeyToMutexMap.computeIfAbsent(it) { Mutex() }.withLock {
                credentialStore.clearToken(it)
                credKeyToAuthJobMap.remove(it)
            }
        }
    }

    private fun onLifetimeTermination(lifetime: Lifetime, credentialKey: CredentialKey, job: Deferred<String>) {
        val lifetimes = lifetimesRequestingToken[credentialKey]!!
        lifetimes.remove(lifetime)
        if (lifetimes.isEmpty()) {
            job.cancel()
        }
    }
    /**
     * Returns the authentication service host used by the route
     */
    suspend fun getAuthHost(route: URI, pomeriumPort: Int = 443): String {
        // port and state is not ever used, but we have to pass some port to Pomerium
        return getAuthLink(route, pomeriumPort, 8080, "dummy").host
    }

    private suspend fun getAuthLink(route: URI, pomeriumPort: Int, callbackServerPort: Int, state: String): URI {
        val uri = URIBuilder(route)
            .setScheme(if (pomeriumPort == 443) "https" else "http")
            .setPort(pomeriumPort)
            .setPath(POMERIUM_LOGIN_ENDPOINT)
            .setParameter(POMERIUM_LOGIN_REDIRECT_PARAM, "http://localhost:$callbackServerPort")
            .setParameter("state", state)
            .build()
        val link = client.get(uri.toURL()).bodyAsText()
        return URI.create(link)
    }


    /**
     * Closes the shared callback server and releases all associated resources.
     * Should be called when the PomeriumAuthProvider instance is no longer needed.
     */
    fun close() {
        LOG.debug("Closing PomeriumAuthProvider and releasing shared callback server")
        sharedCallbackServer.close()
    }

    companion object {
        const val POMERIUM_LOGIN_ENDPOINT = "/.pomerium/api/v1/login"
        const val POMERIUM_LOGIN_REDIRECT_PARAM = "pomerium_redirect_uri"
        const val POMERIUM_JWT_QUERY_PARAM = "pomerium_jwt"

        private val LOG = LoggerFactory.getLogger(PomeriumAuthProvider::class.java.name)

        fun getCredString(authLink: URI): CredentialKey = "Pomerium instance ${authLink.host}"
    }

    /**
     * Shared callback server that prevents Netty EventLoopGroup proliferation.
     * All authentication requests use the same Netty server instance.
     * It uses thread-safe implementation with proper synchronization.
     */
    private class SharedCallbackServer : Closeable {
        private val tokenFutures = ConcurrentHashMap<String, CompletableDeferred<String>>()
        private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
        private var serverPort: Int = 0
        private val serverMutex = kotlinx.coroutines.sync.Mutex()
        
        init {
            runBlocking { startServer() }
        }
        
        private suspend fun startServer() {
            serverMutex.withLock {
                if (server == null) {
                    server = embeddedServer(Netty, 0) {
                        routing {
                            get("/") {
                                val jwtQuery = call.parameters[POMERIUM_JWT_QUERY_PARAM]
                                val state = call.parameters["state"] // Use state to identify the request
                                
                                if (jwtQuery != null && state != null) {
                                    call.respondText(RESPONSE)
                                    tokenFutures[state]?.complete(jwtQuery)
                                } else {
                                    call.respondText(RESPONSE_FAILURE)
                                }
                            }
                            route("*") {
                                handle {
                                    call.respondText(RESPONSE_FAILURE)
                                }
                            }
                        }
                    }
                    server!!.start()
                    serverPort = server!!.engine.resolvedConnectors().first().port
                    LOG.debug("Started shared callback server on port $serverPort")
                }
            }
        }
        
        fun getPort(): Int {
            return serverPort
        }
        
        suspend fun getToken(state: String): String {
            val tokenFuture = CompletableDeferred<String>()
            tokenFutures[state] = tokenFuture
            
            try {
                return tokenFuture.await()
            } finally {
                tokenFutures.remove(state)
            }
        }
        
        override fun close() {
            runBlocking {
                serverMutex.withLock {
                    server?.let { s ->
                        try {
                            s.stop(1000, 2000) // Stop with 1s grace period, 2s timeout
                            LOG.info("Stopped shared callback server")
                        } catch (e: Exception) {
                            LOG.warn("Error stopping shared callback server", e)
                        }
                    }
                    server = null
                }
            }
        }
        
        companion object {
            const val RESPONSE = "Authentication successful. You may now close this tab."
            const val RESPONSE_FAILURE = "Failed to capture Pomerium jwt."
        }
    }
}
package com.salesforce.pomerium

import com.jetbrains.rd.framework.util.startAsync
import com.jetbrains.rd.framework.util.synchronizeWith
import com.jetbrains.rd.util.lifetime.Lifetime
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.http.client.utils.URIBuilder
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for getting authentication for pomerium controlled routes.
 * Handles caching and reuse by using the pomerium authentication host as a key
 * When a new token is needed, this code will perform a device flow to obtain and cache the token from Pomerium
 */
class PomeriumAuthProvider (
    private val credentialStore: CredentialStore,
    private val linkHandler: AuthLinkHandler = OpenBrowserAuthLinkHandler(),
    private val pomeriumPort: Int = 443
) : AuthProvider {

    private val credKeyToMutexMap = ConcurrentHashMap<CredentialKey, Mutex>()
    private val credKeyToAuthJobMap = HashMap<CredentialKey, Deferred<String>>()
    private val routeToCredKeyMap = HashMap<URI, CredentialKey>()
    private val lifetimesRequestingToken = HashMap<CredentialKey, MutableSet<Lifetime>>()
    private val existingRoutes = HashSet<URI>()

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

            val callbackServer = PomeriumAuthCallbackServer()
            val serverPort = callbackServer.start()

            LOG.info("Starting HTTP server on port ${serverPort} for pomerium auth token callback")

            val authLink = getAuthLink(route, pomeriumPort, serverPort)
            val credString = getCredString(authLink)
            return@withContext credKeyToMutexMap.computeIfAbsent(credString) { Mutex() }.withLock {
                credentialStore.getToken(credString)?.let { auth ->
                    callbackServer.close()
                    return@withLock CompletableDeferred(auth)
                }
                credKeyToAuthJobMap[credString]?.let {
                    callbackServer.close()
                    LOG.debug("Existing auth job found, reusing job")
                    lifetimesRequestingToken[credString]!!.add(lifetime)
                    lifetime.onTermination {
                        onLifetimeTermination(lifetime, credString, it)
                    }
                    return@withLock it
                }
                routeToCredKeyMap[route] = credString
                val isNewRoute = existingRoutes.add(route)
                val getToken = jobLifetime.startAsync(Dispatchers.Default) {
                    try {
                        val auth = callbackServer.getToken()
                        LOG.info("Successfully acquired Pomerium authentication")
                        credentialStore.setToken(credString, auth)
                        return@startAsync auth
                    } finally {
                        withContext(NonCancellable) {
                            credKeyToMutexMap[credString]!!.withLock {
                                credKeyToAuthJobMap.remove(credString)
                                callbackServer.close()
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

                linkHandler.handleAuthLink({
                    runBlocking {
                        getAuthLink(route, pomeriumPort, serverPort)
                    }
                }, jobLifetime, isNewRoute, route)

                return@withLock getToken
            }
        }

    override suspend fun invalidate(route: URI) {
        (routeToCredKeyMap.remove(route) ?: run {
            //Port does not matter in this case
            val link = getAuthLink(route, pomeriumPort, 8080)
            getCredString(link)
        }).let {
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

    companion object {
        const val POMERIUM_LOGIN_ENDPOINT = "/.pomerium/api/v1/login"
        const val POMERIUM_LOGIN_REDIRECT_PARAM = "pomerium_redirect_uri"
        const val POMERIUM_JWT_QUERY_PARAM = "pomerium_jwt"

        private val LOG = LoggerFactory.getLogger(PomeriumAuthProvider::class.java.name)
        private val client = HttpClient()

        fun getCredString(authLink: URI): CredentialKey = "Pomerium instance ${authLink.host}"

        /**
         * Returns the authentication service host used by the route
         */
        suspend fun getAuthHost(route: URI, pomeriumPort: Int = 443): String {
            // port 8080 is not ever used, but we have to pass some port to Pomerium
            return getAuthLink(route, pomeriumPort, 8080).host
        }

        private suspend fun getAuthLink(route: URI, pomeriumPort: Int, callbackServerPort: Int): URI {
            val uri = URIBuilder(route)
                .setScheme(if (pomeriumPort == 443) "https" else "http")
                .setPort(pomeriumPort)
                .setPath(POMERIUM_LOGIN_ENDPOINT)
                .setParameter(POMERIUM_LOGIN_REDIRECT_PARAM, "http://localhost:$callbackServerPort")
                .build()
            val link = client.get(uri.toURL()).bodyAsText()
            return URI.create(link)
        }
    }

    private class PomeriumAuthCallbackServer : Closeable {
        val tokenFuture = CompletableDeferred<String>()

        val server = embeddedServer(Netty, 0) {
            routing {
                get("/") {
                    val jwtQuery = call.parameters[POMERIUM_JWT_QUERY_PARAM]
                    if (jwtQuery != null) {
                        call.respondText(RESPONSE)
                        tokenFuture.complete(jwtQuery)
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

        suspend fun start(): Int {
            return server.start().resolvedConnectors().first().port
        }

        override fun close() {
            server.stop()
        }

        suspend fun getToken(): String {
            return tokenFuture.await()
        }

        companion object {
            const val RESPONSE = "Authentication successful. You may now close this tab."
            const val RESPONSE_FAILURE = "Failed to capture Pomerium jwt."
        }

    }
}
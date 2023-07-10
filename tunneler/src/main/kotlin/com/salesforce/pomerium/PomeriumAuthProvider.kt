package com.salesforce.pomerium

import com.jetbrains.rd.framework.util.startAsync
import com.jetbrains.rd.framework.util.synchronizeWith
import com.jetbrains.rd.util.lifetime.Lifetime
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.HttpClients
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
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
            val handler = PomeriumAuthCallbackServer()
            val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).also {
                it.createContext("/", handler)
                it.start()
            }

            LOG.info("Starting HTTP server on port ${server.address.port} for pomerium auth token callback")

            val authLink = getAuthLink(route, pomeriumPort, server.address.port)
            val credString = getCredString(authLink)
            return@withContext credKeyToMutexMap.computeIfAbsent(credString) { Mutex() }.withLock {
                credentialStore.getToken(credString)?.let { auth ->
                    return@withLock CompletableDeferred(auth)
                }
                credKeyToAuthJobMap[credString]?.let {
                    server.stop(0)
                    LOG.debug("Existing auth job found, reusing job")
                    lifetimesRequestingToken[credString]!!.add(lifetime)
                    lifetime.onTermination {
                        onLifetimeTermination(lifetime, credString, it)
                    }
                    return@withLock it
                }
                routeToCredKeyMap.put(route, credString)
                val isNewRoute = existingRoutes.add(route)
                val getToken = jobLifetime.startAsync(Dispatchers.Default) {
                    try {
                        val auth = handler.getToken()
                        LOG.info("Successfully acquired Pomerium authentication")
                        credentialStore.setToken(credString, auth)
                        return@startAsync auth
                    } finally {
                        withContext(NonCancellable) {
                            credKeyToMutexMap[credString]!!.withLock {
                                credKeyToAuthJobMap.remove(credString)
                                server.stop(0)
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

                linkHandler.handleAuth({
                    getAuthLink(route, pomeriumPort, server.address.port)
                }, jobLifetime, isNewRoute)

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

        fun getCredString(authLink: URI): CredentialKey = "Pomerium instance ${authLink.host}"

        private fun getAuthLink(route: URI, pomeriumPort: Int, callbackServerPort: Int): URI {
            val uri = URIBuilder(route)
                .setScheme(if (pomeriumPort == 443) "https" else "http")
                .setPort(pomeriumPort)
                .setPath(POMERIUM_LOGIN_ENDPOINT)
                .setParameter(POMERIUM_LOGIN_REDIRECT_PARAM, "http://localhost:$callbackServerPort")
                .build()
            return HttpClients.createSystem().use { client ->
                client.execute(HttpGet(uri)).use {
                    LOG.debug("Fetched auth link from Pomerium")
                    URI.create(it.entity.content.readAllBytes().decodeToString())
                }
            }
        }
    }

    private class PomeriumAuthCallbackServer : HttpHandler {
        private val token = CompletableFuture<String>()

        override fun handle(exchange: HttpExchange) {
            LOG.debug("Handling http exchange")
            if ("get".equals(exchange.requestMethod, ignoreCase = true)) {
                exchange.requestURI.query.split("&")
                    .filter { it.startsWith(POMERIUM_JWT_QUERY_PARAM) }
                    .map { it.split("=")[1] }
                    .firstOrNull()?.let {
                        exchange.sendResponseHeaders(200, RESPONSE.length.toLong())
                        exchange.responseBody.write(RESPONSE.encodeToByteArray())
                        token.complete(it)
                    } ?: {
                    LOG.warn("Pomerium auth callback did not contain expected jwt query param")
                    exchange.sendResponseHeaders(500, RESPONSE_FAILURE.length.toLong())
                    exchange.responseBody.write(RESPONSE_FAILURE.encodeToByteArray())
                }
            } else {
                LOG.warn("Unknown request method arrived in the Pomerium auth callback: ${exchange.requestMethod}")
            }
        }

        suspend fun getToken(): String {
            return token.await()
        }

        companion object {
            const val RESPONSE = "Authentication successful. You may now close this tab."
            const val RESPONSE_FAILURE = "Failed to capture Pomerium jwt."
        }

    }
}
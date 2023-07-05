package com.salesforce.intellij.gateway.pomerium

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.serviceContainer.NonInjectable
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.AppIcon
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.rd.framework.util.launch
import com.jetbrains.rd.framework.util.startAsync
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
import org.jetbrains.annotations.TestOnly
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.AbstractAction

/**
 * Service for getting authentication for pomerium controlled routes.
 * Handles caching and reuse by using the pomerium authentication host as a key
 * When a new token is needed, this code will perform a device flow to obtain and cache the token from Pomerium
 */
class PomeriumAuthService @TestOnly @NonInjectable internal constructor(
    private val pomeriumPort: Int
) : PomeriumAuthProvider {

    constructor() : this(443)

    private val mutexMap = ConcurrentHashMap<CredentialAttributes, Mutex>()
    private val jobMap = HashMap<CredentialAttributes, Deferred<String>>()
    private val routeCache = HashMap<URI, CredentialAttributes>()

    override suspend fun getAuth(route: URI, lifetime: Lifetime): Deferred<String> = withContext(Dispatchers.Default) {
        //Check for existing job. Note, this is not guaranteed to be thread safe, but it does not require a network call.
        //There is another, thread-safe check below.
        routeCache[route]?.let {
            mutexMap.computeIfAbsent(it) { Mutex() }.withLock {
                PasswordSafe.instance.getPassword(it)?.let { auth ->
                    return@withContext CompletableDeferred(auth)
                }
                jobMap[it]?.let { job ->
                    return@withContext job
                }
            }
        }

        val childLifetime = lifetime.createNested()
        val handler = PomeriumAuthCallbackServer()
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).also {
            it.createContext("/", handler)
            it.start()
        }

        childLifetime.onTermination {
            server.stop(0)
        }


        val openBrowser = {
            val authLink = getAuthLink(route, pomeriumPort, server.address.port)
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                BrowserUtil.browse(authLink)
            }
        }

        val authLink = getAuthLink(route, pomeriumPort, server.address.port)
        val credString = getCredString(authLink)
        mutexMap.computeIfAbsent(credString) { Mutex() }.withLock {
            PasswordSafe.instance.getPassword(credString)?.let { auth ->
                return@withContext CompletableDeferred(auth)
            }
            jobMap[credString]?.let {
                childLifetime.terminate()
                return@withContext it
            }

            val isNewRoute = routeCache.put(route, credString) == null
            val getToken = lifetime.startAsync(Dispatchers.Default) {
                try {
                    val auth = handler.getToken()
                    PasswordSafe.instance.setPassword(credString, auth)
                    return@startAsync auth
                } finally {
                    withContext(NonCancellable) {
                        mutexMap[credString]!!.withLock {
                            jobMap.remove(credString)
                            childLifetime.terminate()
                        }

                    }
                }
            }
            jobMap[credString] = getToken

            val willOpenBrowser = ApplicationManager.getApplication().isActive || isNewRoute

            if (!ApplicationManager.getApplication().isUnitTestMode) {
                childLifetime.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
                    val dialog = DialogBuilder().centerPanel(panel {
                        row {
                            label(
                                if (willOpenBrowser) "Authenticating... Check browser to continue."
                                else "Authentication required. Continue by authenticating in browser."
                            ).align(AlignX.CENTER).also {
                                it.component.icon = AnimatedIcon.Default()
                            }
                        }
                    })
                    dialog.addAction(object : AbstractAction(if (willOpenBrowser) "Reopen in browser" else "Open in browser") {
                        override fun actionPerformed(e: ActionEvent?) {
                            try {
                                openBrowser()
                            } catch (e: Exception) {
                                log.error("Failed to open browser to authenticate with Pomerium", e)
                            }
                        }

                    })
                    dialog.addAction(object : AbstractAction("Copy browser link") {
                        override fun actionPerformed(e: ActionEvent?) {
                            try {
                                CopyPasteManager.getInstance()
                                    .setContents(StringSelection(getAuthLink(route, pomeriumPort, server.address.port).toString()))
                            } catch (e: Exception) {
                                log.error("Failed to copy authentication link for Pomerium", e)
                            }
                        }

                    })
                    dialog.addCancelAction()
                    childLifetime.onTermination {
                        runInEdt(ModalityState.any()) {
                            dialog.dialogWrapper.close(0)
                        }
                    }
                    when (dialog.show()) {
                        DialogWrapper.CANCEL_EXIT_CODE -> {
                            getToken.cancel()
                        }

                        else -> {
                            //all good
                        }
                    }

                    childLifetime.terminate()
                }
            }

            if (!ApplicationManager.getApplication().isUnitTestMode) {
                // Do not open browser when not active except when a new route is being requested
                if (willOpenBrowser) {
                    BrowserUtil.browse(authLink)
                } else {
                    runInEdt {
                        AppIcon.getInstance().requestFocus()
                    }
                }
            }
            return@withContext getToken
        }
    }

    override suspend fun invalidate(route: URI) {
        (routeCache.remove(route) ?: run {
            val link = getAuthLink(route, pomeriumPort, 8080)
            getCredString(link)
        }).let {
            mutexMap.computeIfAbsent(it) { Mutex() }.withLock {
                PasswordSafe.instance.setPassword(it, null)
                jobMap.remove(it)
            }
        }
    }

    companion object {
        const val POMERIUM_LOGIN_ENDPOINT = "/.pomerium/api/v1/login"
        const val POMERIUM_LOGIN_REDIRECT_PARAM = "pomerium_redirect_uri"
        const val POMERIUM_JWT_QUERY_PARAM = "pomerium_jwt"

        private val log = Logger.getInstance(PomeriumAuthService::class.java)

        val instance: PomeriumAuthService
            get() = ApplicationManager.getApplication().getService(PomeriumAuthService::class.java)

        internal fun getCredString(authLink: URI) = CredentialAttributes("Pomerium instance ${authLink.host}")
        private fun getAuthLink(route: URI, pomeriumPort: Int, callbackServerPort: Int): URI {
            val uri = URIBuilder(route)
                .setScheme(if (pomeriumPort == 443) "https" else "http")
                .setPort(pomeriumPort)
                .setPath(POMERIUM_LOGIN_ENDPOINT)
                .setParameter(POMERIUM_LOGIN_REDIRECT_PARAM, "http://localhost:$callbackServerPort")
                .build()
            return HttpClients.createSystem().use { client ->
                client.execute(HttpGet(uri)).use {
                    URI.create(it.entity.content.readAllBytes().decodeToString())
                }
            }
        }
    }

    private class PomeriumAuthCallbackServer : HttpHandler {
        private val token = CompletableFuture<String>()

        override fun handle(exchange: HttpExchange) {
            if ("get".equals(exchange.requestMethod, ignoreCase = true)) {
                exchange.requestURI.query.split("&")
                    .filter { it.startsWith(POMERIUM_JWT_QUERY_PARAM) }
                    .map { it.split("=")[1] }
                    .firstOrNull()?.let {
                        exchange.sendResponseHeaders(200, RESPONSE.length.toLong())
                        exchange.responseBody.write(RESPONSE.encodeToByteArray())
                        token.complete(it)
                    } ?: {
                    exchange.sendResponseHeaders(500, RESPONSE_FAILURE.length.toLong())
                    exchange.responseBody.write(RESPONSE_FAILURE.encodeToByteArray())
                }
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

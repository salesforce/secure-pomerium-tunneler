package com.salesforce.intellij.gateway.pomerium

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.serviceContainer.NonInjectable
import com.jetbrains.rd.framework.util.launch
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.TestOnly
import rawhttp.core.RawHttp
import java.io.IOException
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds

class PomeriumTunneler @TestOnly @NonInjectable internal constructor(
    private val authProvider: PomeriumAuthProvider,
    private val soTimeout: Long,
    private val useTls: Boolean
) : Disposable {

    private val openTunnels = HashSet<URI>()

    constructor() : this(PomeriumAuthService.instance, 10.seconds.inWholeMilliseconds, true)

    suspend fun startTunnel(route: URI,
                            lifetime: LifetimeDefinition,
                            pomeriumHost: String = route.host,
                            pomeriumPort: Int = 443): Int {

        authProvider.getAuth(route, lifetime).await() //Populate auth if required

        val selectorManager = SelectorManager(Dispatchers.IO)
        val localServerSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
        openTunnels.add(route)

        lifetime.onTermination {
            openTunnels.remove(route)
            localServerSocket.close()
            selectorManager.close()
        }

        lifetime.launch(Dispatchers.Default) {
            while (isActive) {
                val localSocket = try {
                    localServerSocket.accept()
                } catch (e: Exception) {
                    LOG.infoWithDebug("Local tunneling socket failed to accept connection", e)
                    continue
                }
                launch(Dispatchers.IO) {
                    LOG.debug("New connection established on local socket for tunneling")
                    try {
                        val auth = withTimeout(soTimeout) {
                            authProvider.getAuth(route, lifetime).await()
                        }
                        val localWriteChannel = localSocket.openWriteChannel(true)
                        val localReadChannel = localSocket.openReadChannel()
                        aSocket(selectorManager)
                            .tcp()
                            .connect(pomeriumHost, pomeriumPort) {
                                keepAlive = true
                                socketTimeout = soTimeout
                            }.configure(useTls).use { tunnelSocket ->
                                val writeChannel = tunnelSocket.openWriteChannel(true)
                                val readChannel = tunnelSocket.openReadChannel()
                                val inputStream = readChannel.toInputStream()

                                writeChannel.writeStringUtf8(
                                    "CONNECT ${route.authority} HTTP/1.1\n" +
                                            "Host: ${route.authority}\n" +
                                            "Accept: */*\n" +
                                            "Connection: keep-alive\n" +
                                            "User-Agent: kotlin/gateway-connector\n" +
                                            "Authorization: Pomerium $auth\n\n"
                                )
                                val response = RawHttp().parseResponse(inputStream)

                                when (response.statusCode) {
                                    200 -> {
                                        LOG.info("Pomerium tunnel established")
                                        launch(Dispatchers.IO) {
                                            try {
                                                localReadChannel.joinTo(writeChannel, true)
                                            } catch (e: Exception) {
                                                handleException(e)
                                            } finally {
                                                withContext(NonCancellable) {
                                                    try {
                                                        tunnelSocket.close()
                                                    } catch (e: Exception) {
                                                        //Do nothing
                                                    }
                                                }
                                            }
                                        }
                                        try {
                                            readChannel.joinTo(localWriteChannel, true)
                                        } catch (e: Exception) {
                                            handleException(e)
                                        } finally {
                                            withContext(NonCancellable) {
                                                try {
                                                    tunnelSocket.close()
                                                } catch (e: Exception) {
                                                    //Do nothing
                                                }
                                            }
                                        }
                                    }

                                    301, 302, 307, 308 -> {
                                        LOG.info("Pomerium token expired. Refreshing...")
                                        authProvider.invalidate(route)
                                    }

                                    503 -> {
                                        LOG.debug("Pomerium returned service unavailable, trying after delay")
                                        delay(30.seconds)
                                        //don't delete jwt
                                    }

                                    else -> {
                                        //Error state
                                        LOG.error("Unknown status code returned from Pomerium: ${response.statusCode}")
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        when (e) {
                            is CancellationException, is ProcessCanceledException -> {
                                // Don't propagate
                            }

                            is UnresolvedAddressException -> delay(1.seconds)
                            else -> LOG.error("Exception occurred during local tunneling", e)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            localSocket.close()
                        }
                    }
                }
            }
        }.invokeOnCompletion { e ->
            if (e is CancellationException) {
                lifetime.terminate()
            } else {
                LOG.error("Unhandled exception in tunneling coroutine", e)
            }
        }

        return localServerSocket.localAddress.toJavaAddress().port
    }

    fun isTunneling() = openTunnels.isNotEmpty()

    private suspend fun Socket.configure(useTls: Boolean): Socket {
        return if (useTls) {
            val handler = CoroutineExceptionHandler { _, throwable ->
                LOG.error("Exception in the tunnel TLS translation", throwable)
            }
            tls(Dispatchers.IO + handler) {
                //TODO, why does this not work?
                trustManager = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
                }
            }
        } else {
            this
        }
    }

    private fun handleException(e: Throwable?) {
        if (e != null) {
            when (e) {
                is IOException -> LOG.debug("IO exception during pomerium tunneling")
                is ClosedSendChannelException, is CancellationException -> { /*Do nothing for this case*/
                }

                else -> LOG.error("Exception while tunneling traffic", e)
            }
        }
    }

    companion object {
        val instance: PomeriumTunneler
            get() = ApplicationManager.getApplication().getService(PomeriumTunneler::class.java)

        private val LOG = Logger.getInstance(PomeriumTunneler::class.java)
    }

    override fun dispose() {
        //Do nothing
    }
}
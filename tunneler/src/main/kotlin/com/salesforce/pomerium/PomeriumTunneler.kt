package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.threading.coroutines.launch
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import rawhttp.core.errors.InvalidHttpResponse
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset
import javax.net.ssl.TrustManager
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.seconds

class PomeriumTunneler(
    private val authProvider: AuthProvider,
    private val soTimeout: Long = 10.seconds.inWholeMilliseconds,
    private val useTls: Boolean = true,
    private val trustManager: TrustManager? = null
) {

    private val openTunnels = HashSet<URI>()
    // Shared SelectorManager to prevent file descriptor exhaustion
    private val selectorManager = SelectorManager(Dispatchers.IO)

    suspend fun startTunnel(
        route: URI,
        lifetime: Lifetime,
        pomeriumHost: String = route.host,
        pomeriumPort: Int = 443
    ): Int = withContext(Dispatchers.Default) {

        authProvider.getAuth(route, lifetime).await() //Populate auth if required

        val localServerSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
        val port = localServerSocket.localAddress.toJavaAddress().port
        openTunnels.add(route)

        LOG.info("Starting local tunnel on 127.0.0.1:$port and tunneling to $route")

        lifetime.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    val localSocket = try {
                        localServerSocket.accept()
                    } catch (e: Exception) {
                        LOG.info("Local tunneling socket failed to accept connection")
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
                                }.configure(useTls, pomeriumHost, trustManager).use { tunnelSocket ->
                                    val writeChannel = tunnelSocket.openWriteChannel(true)
                                    val readChannel = tunnelSocket.openReadChannel()
                                    val outputStream = writeChannel.toOutputStream()
                                    val inputStream = readChannel.toInputStream()

                                    RawHttpRequest(
                                        RequestLine("CONNECT", route, HttpVersion.HTTP_1_1),
                                        RawHttpHeaders.newBuilder()
                                            .with("Host", route.authority)
                                            .with("Accept", "*/*")
                                            .with("Connection", "keep-alive")
                                            .with("User-Agent", "kotlin/tunneler")
                                            .with("Authorization", "Pomerium $auth")
                                            .build(), null, null
                                    ).apply {
                                        LOG.debug("Initializing tunnel by sending CONNECT",)
                                        writeTo(outputStream)
                                    }

                                    val response = try {
                                        RawHttp().parseResponse(inputStream)
                                    } catch (e: InvalidHttpResponse) {
                                        //Expected if the remote socket is no longer active to return a bad response
                                        if (!readChannel.isClosedForRead) {
                                            LOG.warn("Invalid response from Pomerium: ${e.message}")
                                        } else {
                                            LOG.debug("Remote read channel closed during tunnel initialization")
                                        }
                                        return@use
                                    }

                                    when (response.statusCode) {
                                        200 -> {
                                            LOG.info("Pomerium tunnel established")
                                            val writer = launch(Dispatchers.IO + CoroutineName("tunneler-writer")) {
                                                try {
                                                    localReadChannel.copyAndClose(writeChannel)
                                                } catch (e: Exception) {
                                                    handleException(e)
                                                }
                                            }
                                            val reader = launch(Dispatchers.IO + CoroutineName("tunneler-reader")) {
                                                try {
                                                    readChannel.copyAndClose(localWriteChannel)
                                                } catch (e: Exception) {
                                                    handleException(e)
                                                }
                                            }
                                            joinAll(writer, reader)
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
                                            val body = response.body.getOrNull().use {
                                                it?.asRawString(Charset.defaultCharset())
                                            }
                                            LOG.error("Unknown status code returned from Pomerium: ${response.statusCode} message: $body")
                                        }
                                    }
                                }
                        } catch (e: Exception) {
                            when (e) {
                                is CancellationException -> {
                                    // Don't propagate
                                }

                                is UnresolvedAddressException -> delay(1.seconds)
                                is IOException -> {
                                    if (e.message?.contains("Too many open files") == true) {
                                        LOG.error("File descriptor exhaustion detected")
                                        LOG.error("Consider increasing OS file descriptor limits or reducing concurrent tunnels")
                                        // Add delay to prevent cascade failures
                                        delay(5.seconds)
                                    } else {
                                        LOG.error("IO exception during local tunneling", e)
                                    }
                                }
                                is IllegalStateException -> {
                                    if (e.message?.contains("failed to create a child event loop") == true) {
                                        LOG.error("Netty event loop creation failed - likely due to file descriptor exhaustion")
                                        LOG.error("This indicates the system has run out of file descriptors")
                                        // Add longer delay for Netty issues
                                        delay(10.seconds)
                                    } else {
                                        LOG.error("Illegal state exception during local tunneling", e)
                                    }
                                }
                                else -> LOG.error("Exception occurred during local tunneling", e)
                            }
                        } finally {
                            withContext(NonCancellable) {
                                localSocket.close()
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    openTunnels.remove(route)
                    localServerSocket.close()
                    // Don't close selectorManager here since it is shared
                }
            }
        }.invokeOnCompletion { e ->
            if (e !is CancellationException) {
                LOG.error("Unhandled exception in tunneling coroutine", e)
            }
        }

        return@withContext port
    }

    fun isTunneling() = openTunnels.isNotEmpty()

    /**
     * Closes the shared SelectorManager and releases all associated resources.
     * Should be called when the PomeriumTunneler instance is no longer needed.
     * 
     * Note: This will close ALL tunnels using this PomeriumTunneler instance.
     */
    fun close() {
        LOG.info("Closing PomeriumTunneler and releasing shared SelectorManager")
        selectorManager.close()
    }

    private suspend fun Socket.configure(useTls: Boolean, serverName: String, trustManager: TrustManager?): Socket {
        return if (useTls) {
            val handler = CoroutineExceptionHandler { _, throwable ->
                LOG.error("Exception in the tunnel TLS translation", throwable)
            }
            tls(Dispatchers.IO + handler) {
                this.serverName = serverName
                this.trustManager = trustManager
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
        private val LOG = LoggerFactory.getLogger(PomeriumTunneler::class.java.name)
    }
}
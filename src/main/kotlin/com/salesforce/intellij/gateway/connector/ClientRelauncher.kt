package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.threading.coroutines.launch
import com.salesforce.pomerium.TunnelConnectionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When the backend restarts (e.g. after an Xmx change), it drops the client's tunnel connection and the client
 * never dials the tunnel again, even when Gateway sends it a forced join link update. If the client is still
 * present but has opened no new tunnel connection after [wait], [relaunch] replaces it with a new client.
 */
internal class ClientRelauncher(
    private val lifetime: Lifetime,
    private val isClientPresent: () -> Boolean,
    private val relaunch: () -> Unit,
    private val wait: Duration = 30.seconds,
    private val context: CoroutineContext = Dispatchers.Default,
) : TunnelConnectionListener {

    private val openConnections = AtomicInteger()
    private val pendingRelaunch = AtomicReference<Job?>()

    override fun onConnectionOpened() {
        openConnections.incrementAndGet()
        pendingRelaunch.getAndSet(null)?.cancel()
    }

    override fun onConnectionClosed() {
        if (openConnections.decrementAndGet() > 0) return
        val job = lifetime.launch(context) {
            delay(wait)
            if (openConnections.get() > 0) return@launch
            if (!isClientPresent()) {
                LOG.info("Client is not present, not relaunching it")
                return@launch
            }
            LOG.info("No tunnel connection from the client after $wait, relaunching it")
            try {
                relaunch()
            } catch (e: Exception) {
                LOG.warn("Failed to relaunch the client", e)
            }
        }
        pendingRelaunch.getAndSet(job)?.cancel()
    }

    companion object {
        private val LOG = Logger.getInstance(ClientRelauncher::class.java)
    }
}

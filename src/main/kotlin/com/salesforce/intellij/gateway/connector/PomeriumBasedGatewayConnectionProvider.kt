package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.rd.defineNestedLifetime
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.serviceContainer.NonInjectable
import com.intellij.util.net.ssl.CertificateManager
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.salesforce.pomerium.PomeriumAuthProvider
import com.salesforce.pomerium.PomeriumTunneler
import org.jetbrains.annotations.TestOnly
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val POMERIUM_PORT_KEY = "com.salesforce.intellij.pomerium_port"
private val pomeriumPort by lazy {
    Registry.intValue(POMERIUM_PORT_KEY, 443)
}
val PomeriumAuthService by lazy {
    PomeriumAuthProvider(GatewayCredentialStore, GatewayAuthLinkHandler, pomeriumPort,
            authResponseHandler = AuthRedirectResponseHandlerEp.getHandler(),
            sslSocketFactory = CertificateManager.getInstance().sslContext.socketFactory,
            trustManager = CertificateManager.getInstance().trustManager
    )
}
class PomeriumBasedGatewayConnectionProvider @NonInjectable @TestOnly internal constructor(
    private val relaunchWait: Duration = 30.seconds,
    private val createHandle: (lifetime: Lifetime, initialLink: URI, remoteIdentity: String?) -> ThinClientHandle
): GatewayConnectionProvider, Disposable {

    private val tunneler = PomeriumTunneler(PomeriumAuthService,
        useTls = !ApplicationManager.getApplication().isUnitTestMode,
        trustManager = CertificateManager.getInstance().trustManager)

    constructor(): this(createHandle = {lifetime, initialLink, remoteIdentity ->
        LinkedClientManager.getInstance().startNewClient(lifetime, initialLink, remoteIdentity) {
            LOG.debug("Connection established")
        }
    })
    init {
        Disposer.register(PluginDisposable.instance, this)
    }

    override fun isApplicable(parameters: Map<String, String>) = parameters.containsKey(PARAM_ROUTE)

    override suspend fun connect(
        parameters: Map<String, String>,
        requestor: ConnectionRequestor
    ): GatewayConnectionHandle? {
        val pomeriumRouteString = parameters[PARAM_ROUTE]!!.removePrefix("tcp+")
        val connectionKey = parameters[PARAM_CONNECTION_KEY]!!
        val pomeriumInstance = parameters[PARAM_POMERIUM_INSTANCE]
        val pomeriumRoute = URI(pomeriumRouteString)
        val lifetime = defineNestedLifetime()

        //TODO, disabling this check as there is a bug in Gateway that does not properly end the lifetime
//        runningInstances[pomeriumRouteString]?.let {
//            if (it.clientPresent) {
//                LOG.info("Attempting to connect to an existing, ongoing, session")
//                runningInstances[pomeriumRouteString]!!.focusClientWindow()
//                return null
//            }
//            runningInstances.remove(pomeriumRouteString)
//        }
        try {
            // The client currently attached to this connection. Events from a client we replaced are ignored.
            val current = AtomicReference<ThinClientHandle?>()
            lateinit var link: URI

            fun attach(handle: ThinClientHandle) {
                current.set(handle)
                runningInstances[pomeriumRouteString] = Instance(handle,lifetime)
                handle.clientClosed.advise(lifetime) {
                    // A client restart (e.g. after a plugin update) closes the client and reconnects to the same
                    // tunnel, so keep the lifetime alive in that case.
                    val replaced = handle !== current.get()
                    LOG.info("Client closed for $pomeriumRouteString, restartNeeded=${handle.restartNeeded}, replaced=$replaced")
                    if (!replaced && !handle.restartNeeded) {
                        lifetime.terminate()
                    }
                }
                handle.clientFailedToOpenProject.advise(lifetime) {
                    if (handle === current.get()) lifetime.terminate()
                }
                handle.onClientPresenceChanged.advise(lifetime) {
                    if (handle === current.get()) runningInstances[pomeriumRouteString] = Instance(handle,lifetime)
                }
            }

            val relauncher = ClientRelauncher(
                lifetime,
                isClientPresent = { current.get()?.clientPresent == true },
                relaunch = {
                    val stuck = current.getAndSet(null)
                    stuck?.close()
                    attach(createHandle(lifetime, link, pomeriumRoute.toString()))
                },
                relaunchWait
            )
            val port = tunneler.startTunnel(
                pomeriumRoute,
                lifetime,
                pomeriumInstance ?: pomeriumRoute.host,
                pomeriumPort,
                relauncher
            )

            link = clientLink(port, connectionKey)
            attach(createHandle(lifetime,
                    link,
                    pomeriumRoute.toString()))
            lifetime.onTermination {
                val oldHandle = runningInstances.remove(pomeriumRouteString)
                //Prevent removing anything but this handle
                val handle = current.get()
                if (handle != null && oldHandle != handle) {
                    runningInstances[pomeriumRouteString] = Instance(handle,lifetime)
                }
            }

            return object : GatewayConnectionHandle(lifetime) {
                override fun getTitle() = ""

                override fun hideToTrayOnStart() = true

            }
        } catch (e: Exception) {
            LOG.error("Issue while connecting to the instance", e)
            lifetime.terminate()
            return null
        }
    }

    companion object {
        val LOG = Logger.getInstance(PomeriumBasedGatewayConnectionProvider::class.java)

        const val PARAM_ROUTE = "pomeriumRoute"
        const val PARAM_CONNECTION_KEY = "connectionKey"
        const val PARAM_POMERIUM_INSTANCE = "pomeriumInstance"
        data class Instance (val clientHandle: ThinClientHandle, val lifetime: LifetimeDefinition )

        // Points the join link at the local tunnel and keeps its scheme.
        // fp is optional for tcp-direct links, so it is dropped to keep the link valid across restarts.
        internal fun clientLink(port: Int, connectionKey: String): URI {
            val scheme = connectionKey.substringBefore("://")
            val fragment = connectionKey.substringAfter('#', "")
                .split('&')
                .filterNot { scheme == "tcp-direct" && it.startsWith("fp=") }
                .joinToString("&")
            return URI("$scheme://127.0.0.1:$port#$fragment")
        }
        private val runningInstances = HashMap<String, Instance>()
    }

   override fun dispose() {
       LOG.debug("Disposing PomeriumBasedGatewayConnectionProvider")
       runningInstances.forEach { instance -> instance.value.lifetime.terminate()}
       tunneler.close()
   }
}

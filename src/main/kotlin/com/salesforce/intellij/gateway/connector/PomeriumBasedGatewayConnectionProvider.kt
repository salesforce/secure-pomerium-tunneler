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
import com.salesforce.pomerium.PomeriumAuthProvider
import com.salesforce.pomerium.PomeriumTunneler
import org.jetbrains.annotations.TestOnly
import java.net.URI

const val POMERIUM_PORT_KEY = "com.salesforce.intellij.pomerium_port"
private val pomeriumPort by lazy {
    Registry.intValue(POMERIUM_PORT_KEY, 443)
}
val PomeriumAuthService by lazy {
    PomeriumAuthProvider(GatewayCredentialStore, GatewayAuthLinkHandler, pomeriumPort,
            CertificateManager.getInstance().sslContext.socketFactory,
            CertificateManager.getInstance().trustManager
    )
}
class PomeriumBasedGatewayConnectionProvider @NonInjectable @TestOnly internal constructor(
    private val createHandle: (lifetime: Lifetime, initialLink: URI, remoteIdentity: String?) -> ThinClientHandle
): GatewayConnectionProvider, Disposable {

    private val tunneler = PomeriumTunneler(PomeriumAuthService,
        useTls = !ApplicationManager.getApplication().isUnitTestMode,
        trustManager = CertificateManager.getInstance().trustManager)

    constructor(): this({lifetime, initialLink, remoteIdentity ->
        LinkedClientManager.getInstance().startNewClient(lifetime, initialLink, remoteIdentity) {
            LOG.debug("Connection established")
        }
    })
    init {
        Disposer.register(ApplicationListenerImpl.instance, this)
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
            val port = tunneler.startTunnel(
                pomeriumRoute,
                lifetime,
                pomeriumInstance ?: pomeriumRoute.host,
                pomeriumPort
            )

            val handle = createHandle(lifetime,
                    URI("tcp://localhost:${port}${connectionKey.substring(connectionKey.indexOf("#"))}"),
                    pomeriumRoute.toString())
            lifetime.onTermination {
                val oldHandle = runningInstances.remove(pomeriumRouteString)
                //Prevent removing anything but this handle
                if (oldHandle != handle) {
                    runningInstances[pomeriumRouteString] = handle
                }
            }
            handle.clientClosed.advise(lifetime) {
                lifetime.terminate()
            }
            handle.clientFailedToOpenProject.advise(lifetime) {
                lifetime.terminate()
            }
            handle.onClientPresenceChanged.advise(lifetime) {
                runningInstances[pomeriumRouteString] = handle
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

        private val runningInstances = HashMap<String, ThinClientHandle>()
    }

    override fun dispose() {
        //Do nothing
    }
}
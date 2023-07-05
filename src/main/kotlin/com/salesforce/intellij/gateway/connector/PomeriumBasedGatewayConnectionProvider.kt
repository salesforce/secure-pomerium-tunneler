package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.rd.defineNestedLifetime
import com.intellij.openapi.util.Disposer
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.salesforce.intellij.gateway.pomerium.PomeriumTunneler
import java.net.URI

class PomeriumBasedGatewayConnectionProvider : GatewayConnectionProvider, Disposable {

    init {
        Disposer.register(PomeriumTunneler.instance, this)
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

        runningInstances[pomeriumRouteString]?.let {
            if (it.clientPresent) {
                runningInstances[pomeriumRouteString]!!.focusClientWindow()
                return null
            }
            runningInstances.remove(pomeriumRouteString)
        }
        try {
            val port = PomeriumTunneler.instance.startTunnel(
                pomeriumRoute,
                lifetime,
                pomeriumInstance ?: pomeriumRoute.host
            )
            val handle = LinkedClientManager.getInstance().startNewClient(
                lifetime,
                URI("tcp://localhost:${port}${connectionKey.substring(connectionKey.indexOf("#"))}"),
                pomeriumRoute.toString()
            ) {
                LOG.debug("Connection established")
            }
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
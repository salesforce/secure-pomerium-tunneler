package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import java.net.URI

interface AuthLinkHandler {
    /**
     * Called when authentication is required but not valid for a route. Its up to the implementer
     * to have the user perform authentication in a browser on the same machine at some point.
     * @param getLink Call this to fetch the latest link to direct the user to
     * @param jobLifetime The lifetime of the authentication job. Once authentication is complete, the lifetime will be too
     * @param newRoute True if this is a new route during the JVM lifetime
     */
    fun handleAuthLink(getLink: () -> URI, jobLifetime: LifetimeDefinition,
                       newRoute: Boolean)
}
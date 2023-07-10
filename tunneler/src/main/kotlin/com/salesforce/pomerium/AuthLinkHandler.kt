package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import java.net.URI

interface AuthLinkHandler {
    fun handleAuth(getLink: () -> URI, jobLifetime: LifetimeDefinition, newRoute: Boolean)
}
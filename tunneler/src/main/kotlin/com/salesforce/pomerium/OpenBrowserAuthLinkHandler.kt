package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import java.awt.Desktop
import java.net.URI

class OpenBrowserAuthLinkHandler : AuthLinkHandler {
    override fun handleAuth(getLink: () -> URI, jobLifetime: LifetimeDefinition, newRoute: Boolean) {
        Desktop.getDesktop().browse(getLink())
    }
}
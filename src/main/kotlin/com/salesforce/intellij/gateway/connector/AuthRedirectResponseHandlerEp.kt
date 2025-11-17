package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.extensions.ExtensionPointName
import com.salesforce.pomerium.AuthenticationRedirectResponseHandler

object AuthRedirectResponseHandlerEp {
    val EP = ExtensionPointName.create<AuthenticationRedirectResponseHandler>("com.salesforce.intellij.sgt.authRedirectResponseHandler")

    fun getHandler(): AuthenticationRedirectResponseHandler = EP.extensions.first()
}

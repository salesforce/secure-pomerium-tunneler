package com.salesforce.pomerium

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall

class DefaultAuthRedirectResponseHandler: AuthenticationRedirectResponseHandler {
    override suspend fun handleAuthenticationSuccess(call: RoutingCall) {
        call.response.status(HttpStatusCode.OK)
        call.respondText(RESPONSE)
    }

    override suspend fun handleAuthenticationFailure(call: RoutingCall) {
        call.response.status(HttpStatusCode.BadRequest)
        call.respondText(RESPONSE_FAILURE)
    }

    companion object {
        const val RESPONSE = "Authentication successful. You may now close this tab."
        const val RESPONSE_FAILURE = "Failed to capture Pomerium jwt."
    }
}
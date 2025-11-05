package com.salesforce.pomerium

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall

interface AuthenticationRedirectResponseHandler {

    suspend fun handleAuthenticationSuccess(call: RoutingCall)

    suspend fun handleAuthenticationFailure(call: RoutingCall)

    fun configureStaticContent(staticRoute: Route) {
        staticRoute.handle {
            call.response.status(HttpStatusCode.NotFound)
        }
    }
}
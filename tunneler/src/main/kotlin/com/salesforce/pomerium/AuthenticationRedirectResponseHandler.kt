package com.salesforce.pomerium

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall

/**
 * Handler for the token exchange process, enables overriding the default success/failure content of the
 * redirect response
 */
interface AuthenticationRedirectResponseHandler {

    /**
     * Handle a successful authentication redirect where the auth token has been captured
     * Its recommended the implementor set a valid http status code (200 for example)
     */
    suspend fun handleAuthenticationSuccess(call: RoutingCall)

    /**
     * Handle a failure where the redirect is made, but the token could not be captured (wrong or missing parameter)
     * Its recommended the implementor set a valid http status code (400 for example)
     */
    suspend fun handleAuthenticationFailure(call: RoutingCall)

    /**
     * Configure the server to serve content under /static, can be used to serve additional content needed by the success/failure page
     */
    fun configureStaticContent(staticRoute: Route) {
        staticRoute.handle {
            call.response.status(HttpStatusCode.NotFound)
        }
    }
}
package com.salesforce.pomerium

import io.ktor.server.routing.RoutingCall

/**
 * Handler for the token exchange process, enables overriding the default success/failure content of the
 * redirect response
 */
interface AuthenticationRedirectResponseHandler {

    /**
     * Handle a successful authentication redirect where the auth token has been captured
     * It's recommended that the implementor sets a valid http status code (200 for example)
     */
    suspend fun handleAuthenticationSuccess(call: RoutingCall)

    /**
     * Handle a failure where the redirect is made, but the token could not be captured (wrong or missing parameter)
     * It's recommended that the implementor sets a valid http status code (400 for example)
     */
    suspend fun handleAuthenticationFailure(call: RoutingCall)
}
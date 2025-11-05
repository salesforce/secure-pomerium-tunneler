package com.salesforce.pomerium

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*

interface AuthenticationRedirectResponseHandler {

    fun authenticationSuccessMessage(): String

    fun authenticationFailureMessage(): String

    fun configureStaticContent(staticRoute: Route) {
        staticRoute.handle {
            call.response.status(HttpStatusCode.NotFound)
        }
    }
}
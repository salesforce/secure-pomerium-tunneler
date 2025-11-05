package com.salesforce.pomerium

interface AuthenticationRedirectResponseHandler {

    fun authenticationSuccessMessage(): String

    fun authenticationFailureMessage(): String
}
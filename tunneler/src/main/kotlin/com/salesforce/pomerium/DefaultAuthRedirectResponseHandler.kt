package com.salesforce.pomerium

object DefaultAuthRedirectResponseHandler: AuthenticationRedirectResponseHandler {
    const val RESPONSE = "Authentication successful. You may now close this tab."
    const val RESPONSE_FAILURE = "Failed to capture Pomerium jwt."

    override fun authenticationSuccessMessage() = RESPONSE

    override fun authenticationFailureMessage() = RESPONSE_FAILURE
}
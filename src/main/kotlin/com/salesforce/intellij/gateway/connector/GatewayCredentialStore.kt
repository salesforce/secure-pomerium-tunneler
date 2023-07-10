package com.salesforce.intellij.gateway.connector

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.salesforce.pomerium.CredentialKey
import com.salesforce.pomerium.CredentialStore

object GatewayCredentialStore : CredentialStore {
    override fun getToken(key: CredentialKey) = PasswordSafe.instance.getPassword(CredentialAttributes(key))

    override fun setToken(key: CredentialKey, jwt: String) {
        PasswordSafe.instance.setPassword(CredentialAttributes(key), jwt)
    }

    override fun clearToken(key: CredentialKey) {
        PasswordSafe.instance.setPassword(CredentialAttributes(key), null)
    }
}
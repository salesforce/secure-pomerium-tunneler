package com.salesforce.pomerium

typealias CredentialKey = String

interface CredentialStore {
    fun getToken(key: CredentialKey): String?
    fun setToken(key: CredentialKey, jwt: String)
    fun clearToken(key: CredentialKey)
}
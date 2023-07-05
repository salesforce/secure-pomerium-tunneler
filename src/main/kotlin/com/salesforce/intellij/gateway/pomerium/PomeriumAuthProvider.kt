package com.salesforce.intellij.gateway.pomerium

import com.jetbrains.rd.util.lifetime.Lifetime
import kotlinx.coroutines.Deferred
import java.net.URI

interface PomeriumAuthProvider {

    suspend fun getAuth(route: URI, lifetime: Lifetime): Deferred<String>
    suspend fun invalidate(route: URI)
}
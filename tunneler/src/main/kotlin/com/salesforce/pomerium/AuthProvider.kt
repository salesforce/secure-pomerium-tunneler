package com.salesforce.pomerium

import com.jetbrains.rd.util.lifetime.Lifetime
import kotlinx.coroutines.Deferred
import java.net.URI

interface AuthProvider {

    suspend fun getAuth(route: URI, lifetime: Lifetime): Deferred<String>
    suspend fun invalidate(route: URI)
}
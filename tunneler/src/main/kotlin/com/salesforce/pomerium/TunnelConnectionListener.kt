package com.salesforce.pomerium

/**
 * Notified as local connections to a tunnel open and close.
 */
interface TunnelConnectionListener {
    fun onConnectionOpened() {}
    fun onConnectionClosed() {}
}

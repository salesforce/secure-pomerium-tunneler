package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

@Service
class PluginDisposable(): Disposable {

    override fun dispose() {
        //Do nothing
    }

    companion object {
        val instance: Disposable
            get() = ApplicationManager.getApplication().getService(PluginDisposable::class.java)
    }
}
package com.salesforce.intellij.gateway.connector

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CoroutineScope

class ApplicationListenerImpl(val coroutineScope: CoroutineScope): ApplicationListener, AppLifecycleListener, Disposable {

    init {
        ApplicationManager.getApplication().addApplicationListener(this, this)
    }

    override fun dispose() {
        //Do nothing
    }

    companion object {
        val instance: ApplicationListenerImpl
            get() = ApplicationManager.getApplication().getService(ApplicationListenerImpl::class.java)
    }
}
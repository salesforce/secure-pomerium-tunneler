package com.salesforce.intellij.gateway.connector

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.showOkCancelDialog
import com.intellij.openapi.util.Disposer
import com.salesforce.intellij.gateway.pomerium.PomeriumTunneler
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

class ApplicationListenerImpl(val coroutineScope: CoroutineScope): ApplicationListener, AppLifecycleListener, Disposable {

    init {
        Disposer.register(PomeriumTunneler.instance, this)
        ApplicationManager.getApplication().addApplicationListener(this, this)
    }
    override fun canExitApplication(): Boolean {
        if (PomeriumTunneler.instance.isTunneling()) {
            val result = AtomicBoolean(false)
            ApplicationManager.getApplication().invokeAndWait {
                result.set(showOkCancelDialog("Are You Sure?",
                    "There are open projects which will be terminated.\nDo you wish to quit?",
                    "Exit",
                    "Cancel") == Messages.OK)
            }
            return result.get()
        }
        return true
    }

    override fun dispose() {
        //Do nothing
    }

    companion object {
        val instance: ApplicationListenerImpl
            get() = ApplicationManager.getApplication().getService(ApplicationListenerImpl::class.java)
    }
}
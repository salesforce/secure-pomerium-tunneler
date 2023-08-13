package com.salesforce.intellij.gateway.connector

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.AppIcon
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.rd.framework.util.launch
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.salesforce.pomerium.AuthLinkHandler
import kotlinx.coroutines.Dispatchers
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.net.URI
import javax.swing.AbstractAction

object GatewayAuthLinkHandler : AuthLinkHandler {
    private val log = Logger.getInstance(GatewayAuthLinkHandler::class.java)
    override fun handleAuthLink(getLink: () -> URI, jobLifetime: LifetimeDefinition, newRoute: Boolean) {
        val willOpenBrowser = ApplicationManager.getApplication().isActive || newRoute

        if (!ApplicationManager.getApplication().isUnitTestMode) {
            jobLifetime.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
                val dialog = DialogBuilder().centerPanel(panel {
                    row {
                        label(
                            if (willOpenBrowser) "Authenticating... Check browser to continue."
                            else "Authentication required. Continue by authenticating in browser."
                        ).align(AlignX.CENTER).also {
                            it.component.icon = AnimatedIcon.Default()
                        }
                    }
                })
                dialog.addAction(object :
                    AbstractAction(if (willOpenBrowser) "Reopen in browser" else "Open in browser") {
                    override fun actionPerformed(e: ActionEvent?) {
                        try {
                            BrowserUtil.browse(getLink())
                        } catch (e: Exception) {
                            log.error("Failed to open browser to authenticate with Pomerium", e)
                        }
                    }

                })
                dialog.addAction(object : AbstractAction("Copy browser link") {
                    override fun actionPerformed(e: ActionEvent?) {
                        try {
                            CopyPasteManager.getInstance()
                                .setContents(StringSelection(getLink().toString()))
                        } catch (e: Exception) {
                            log.error("Failed to copy authentication link for Pomerium", e)
                        }
                    }

                })
                dialog.addCancelAction()
                jobLifetime.onTermination {
                    runInEdt(ModalityState.any()) {
                        dialog.dialogWrapper.close(0)
                    }
                }
                dialog.show()

                jobLifetime.terminate()
            }
        }

        if (!ApplicationManager.getApplication().isUnitTestMode) {
            // Do not open browser when not active except when a new route is being requested
            if (willOpenBrowser) {
                BrowserUtil.browse(getLink())
            } else {
                runInEdt {
                    AppIcon.getInstance().requestFocus()
                }
            }
        }
    }
}
package com.salesforce.intellij.gateway.connector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.gateway.api.GatewayConnector
import com.jetbrains.gateway.api.GatewayConnectorView
import com.jetbrains.gateway.api.GatewayCustomViewConnector
import com.jetbrains.gateway.api.GatewayCustomViewConnectorContextKind
import com.jetbrains.gateway.api.GatewayUI
import com.jetbrains.rd.util.lifetime.Lifetime
import icons.GatewayCoreIcons.Icons
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Internal testing connector to allow using the console provided link in development
 * instances. Useful since clicking the link in the browser will not launch sandbox instances
 */
class GatewayConnectLinkConnector: GatewayConnector, GatewayCustomViewConnector {
    private val cs = ApplicationListenerImpl.instance.coroutineScope
    override val icon = Icons.Link

    init {
        if (!ApplicationManager.getApplication().isInternal) {
            throw ExtensionNotApplicableException.create()
        }
    }

    override fun getDescription() = "Connect through Pomerium via url"
    override fun getTitle() = "Connection Link"
    override fun getActionText() = "" //Custom view also renders this useless.

    //Custom view is used over this method. Returning an empty panel
    override fun createView(lifetime: Lifetime) = object: GatewayConnectorView {
        override val component = JPanel()
    }

    override fun getCustomView(ctx: GatewayCustomViewConnectorContextKind): JComponent {
        when (ctx) {
            GatewayCustomViewConnectorContextKind.SIDEBAR -> {
                return panel {
                    val connect: (url: String?) -> Unit = {url ->
                        if (url?.isNotBlank() == true) {
                            val fragments = URI(url.trim()).rawFragment?.split("&")
                                ?.toList()?.associateBy({ it.split("=")[0]},
                                    { URLDecoder.decode(it.split("=")[1], Charset.defaultCharset()) }) ?: mapOf()

                            GatewayUI.getInstance().connect(fragments)
                        }
                    }
                    row("URL:") {
                        val textField = textField()
                            .focused()
                            .resizableColumn()
                            .align(AlignX.FILL)
                            .component.apply {
                                addKeyListener(object : KeyAdapter() {
                                    override fun keyPressed(e: KeyEvent) {
                                        if (e.keyCode == KeyEvent.VK_ENTER) {
                                            connect(text)
                                            text = ""
                                        }
                                    }
                                })
                            }

                        button("Connect") {
                            connect(textField.text)
                            textField.text = ""
                        }
                    }.layout(RowLayout.INDEPENDENT)
                }
            }

            else -> {
                return panel {  }
            }
        }
    }


}
package com.salesforce.intellij.gateway.pomerium

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpResponse
import rawhttp.core.StatusLine
import kotlin.random.Random

class MockPomerium: TestRule {
    val token = Random.nextDouble().toString()
    val route = "localhost:2801"
    var requestCount = 0

    private var pomeriumTask: Deferred<*>? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var socketConnection: Deferred<*>? = null

    override fun apply(statement: Statement, description: Description): Statement = object: Statement() {
        override fun evaluate() {
            pomeriumTask = null
            try {
                statement.evaluate()
            } finally {
                if (pomeriumTask != null) {
                    runBlocking {
                        pomeriumTask!!.cancelAndJoin()
                    }
                }
                coroutineScope.cancel()
            }
        }
    }

    suspend fun killConnection() {
        this.socketConnection?.cancelAndJoin()
    }

    suspend fun startMockPomerium(): Int {
        val selectorManager = SelectorManager(Dispatchers.IO)
        val socket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
        pomeriumTask = coroutineScope.async(Dispatchers.IO) {
            while(isActive) {
                try {
                    val it = socket.accept()
                    socketConnection = async {
                        it.use {
                            val readChannel = it.openReadChannel()
                            val writeChannel = it.openWriteChannel(true)
                            val request = RawHttp().parseRequest(readChannel.toInputStream())

                            val auth = request.headers.get("authorization").first()
                            Assert.assertEquals("Pomerium $token", auth)
                            Assert.assertEquals(route, request.startLine.uri.authority)

                            val response = RawHttpResponse(
                                null, null,
                                StatusLine(HttpVersion.HTTP_1_1, 200, "OK"),
                                RawHttpHeaders.empty(),
                                null
                            )
                            response.writeTo(writeChannel.toOutputStream())
                            requestCount++
                            readChannel.joinTo(writeChannel, true)
                        }
                    }
                } catch (e: Exception) {
                    //do nothing
                }
            }
        }
        pomeriumTask!!.invokeOnCompletion {
            socket.close()
            selectorManager.close()
        }
        return socket.localAddress.toJavaAddress().port
    }
}
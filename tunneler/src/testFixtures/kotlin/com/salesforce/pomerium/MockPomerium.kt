package com.salesforce.pomerium

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpResponse
import rawhttp.core.StatusLine
import rawhttp.core.body.EagerBodyReader
import kotlin.random.Random

class MockPomerium : AfterTestExecutionCallback {
    val token = Random.nextDouble().toString()
    val route = "localhost:2801"
    var requestCount = 0

    private var pomeriumTask: kotlinx.coroutines.Deferred<*>? = null
    private val coroutineScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
    private var socketConnection: kotlinx.coroutines.Deferred<*>? = null

    suspend fun killConnection() {
        this.socketConnection?.cancelAndJoin()
    }

    suspend fun startMockPomerium(): Int {
        val selectorManager = SelectorManager(Dispatchers.IO)
        val socket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
        pomeriumTask = coroutineScope.async(Dispatchers.IO) {
            while (isActive) {
                try {
                    val connection = socket.accept()
                    socketConnection = async {
                        connection.use { _ ->
                            val readChannel = connection.openReadChannel()
                            val writeChannel = connection.openWriteChannel(true)
                            val request = rawhttp.core.RawHttp().parseRequest(readChannel.toInputStream())

                            if (request.uri.path == PomeriumAuthProvider.POMERIUM_LOGIN_ENDPOINT) {
                                // This is a hack, it assumes the server is listening for the jwt response
                                // which is typically initiated by a browser, but this is a way to prevent
                                // a browser dependency in tests
                                val redirect = request.uri.query.split("&").first {
                                    it.startsWith(PomeriumAuthProvider.POMERIUM_LOGIN_REDIRECT_PARAM)
                                }.split("=")[1]
                                HttpClients.createSystem()
                                    .execute(HttpGet(redirect + "/?${PomeriumAuthProvider.POMERIUM_JWT_QUERY_PARAM}=$token"))
                                    .use {
                                        Assertions.assertEquals(200, it.code)
                                    }

                                //This would be the response expected
                                val response = RawHttpResponse(
                                    null, null,
                                    StatusLine(HttpVersion.HTTP_1_1, 200, "OK"),
                                    RawHttpHeaders.empty(),
                                    EagerBodyReader("http://auth.example.com".encodeToByteArray())
                                )
                                response.writeTo(writeChannel.toOutputStream())
                            } else {

                                val auth = request.headers.get("authorization").first()
                                Assertions.assertEquals("Pomerium $token", auth)
                                Assertions.assertEquals(route, request.startLine.uri.authority)

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


    override fun afterTestExecution(context: ExtensionContext?) {
        if (pomeriumTask != null) {
            runBlocking {
                pomeriumTask!!.cancelAndJoin()
                pomeriumTask = null
            }
        }
        coroutineScope.cancel()
    }
}
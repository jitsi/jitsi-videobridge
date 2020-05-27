/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.videobridge.api.util

import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.ws
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Duration

/**
 * A websocket client which sends one message at a time, waiting for
 * the response (and returning it) before sending the next message.
 *
 * It also supports a non-blocking 'fire-and-forget' send.
 *
 * All messages are sent as [Frame.Text], every message *must* have a response
 * from the server[1], and all responses *must* be of type [Frame.Text].
 *
 * [1]: Rather than implement a request-response-correlation scheme, this
 * client simply assumes all requests will have a response and that a request
 * will be responded to before the next request is handled.  With this
 * assumption, we can merely handle a response each time we send a request
 * and it will keep requests and responses 'aligned'.
 */
class SynchronousWebSocketClient(
    private val client: HttpClient,
    private val host: String,
    private val port: Int,
    /**
     * The path of the remote websocket URL
     */
    private val path: String,
    parentLogger: Logger,
    private val requestTimeout: Duration = Duration.ofSeconds(5),
    /**
     * The dispatcher which will be used for all of the request and response
     * processing.
     */
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val logger = createChildLogger(parentLogger)
    private val job = Job()
    private val coroutineScope = CoroutineScope(dispatcher + job)
    private val msgsToSend = Channel<Frame>(Channel.RENDEZVOUS)
    private val msgsReceived = Channel<Frame>(Channel.RENDEZVOUS)

    /**
     * Send [data] and block until the response is received, returning the
     * body of the response as a [String].
     */
    @Throws(JvbApiException::class)
    fun sendAndGetReply(data: String): String {
        try {
            val resp = runBlocking(coroutineScope.coroutineContext) {
                withTimeout(requestTimeout.toMillis()) {
                    msgsToSend.send(Frame.Text(data))
                    msgsReceived.receive()
                }
            }
            return when (resp) {
                is Frame.Text -> resp.readText()
                else -> throw IllegalStateException("Expected a text frame response")
            }
        } catch (t: TimeoutCancellationException) {
            throw JvbApiTimeoutException()
        } catch (t: Throwable) {
            throw JvbApiException(t.message)
        }
    }

    /**
     * Send [data] without waiting for its response
     */
    fun sendAndForget(data: String) {
        coroutineScope.launch {
            logger.trace { "sendAndForget running in thread ${Thread.currentThread().name}" }
            msgsToSend.send(Frame.Text(data))
            // Receive the response but ignore it
            msgsReceived.receive()
        }
    }

    /**
     * Establish the websocket connection and start a loop to handle
     * sending and receiving websocket messages.
     */
    fun run() {
        coroutineScope.launch {
            client.ws(host = host, port = port, path = path) {
                try {
                    for (msg in msgsToSend) {
                        send(msg)
                        val resp = incoming.receive()
                        msgsReceived.send(resp)
                    }
                } catch (e: ClosedReceiveChannelException) {
                    logger.info("Websocket was closed")
                    msgsReceived.close(e)
                    return@ws
                } catch (t: Throwable) {
                    logger.error("Error in websocket connection: ", t)
                    msgsReceived.close(t)
                    return@ws
                }
            }
        }
    }

    /**
     * Stop and close the websocket connection
     */
    fun stop() {
        runBlocking {
            job.cancelAndJoin()
        }
    }
}

/**
 * An exception occurred while processing an API request
 */
open class JvbApiException(msg: String?) : Exception(msg)

class JvbApiTimeoutException : JvbApiException("Operation timed out")

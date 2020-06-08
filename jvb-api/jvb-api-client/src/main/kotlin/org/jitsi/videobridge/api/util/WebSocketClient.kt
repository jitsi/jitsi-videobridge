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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * A websocket client which sends messages and invokes a handler upon receiving
 * messages from the far side.  Sending is non-blocking, and the client has no
 * notion of correlating "responses" to "requests": if request/response
 * semantics are required then they must be implemented by a layer on top of
 * this class.
 */
class WebSocketClient(
    private val client: HttpClient,
    private val host: String,
    private val port: Int,
    /**
     * The path of the remote websocket URL
     */
    private val path: String,
    parentLogger: Logger,
    var incomingMessageHandler: (Frame) -> Unit = {},
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

    fun sendString(data: String) {
        coroutineScope.launch {
            msgsToSend.send(Frame.Text(data))
        }
    }

    /**
     * Establish the websocket connection and start a loop to handle
     * sending and receiving websocket messages.
     */
    fun run() {
        coroutineScope.launch {
            client.ws(host = host, port = port, path = path) {
                launch {
                    for (msg in incoming) {
                        incomingMessageHandler(msg)
                    }
                }
                try {
                    for (msg in msgsToSend) {
                        send(msg)
                    }
                } catch (e: ClosedReceiveChannelException) {
                    logger.info("Websocket was closed")
                    return@ws
                } catch (e: CancellationException) {
                    logger.info("Websocket job was cancelled")
                    throw e
                } catch (t: Throwable) {
                    logger.error("Error in websocket connection: ", t)
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

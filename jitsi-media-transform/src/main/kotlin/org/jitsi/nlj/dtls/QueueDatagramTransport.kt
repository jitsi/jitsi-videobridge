/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.dtls

import org.bouncycastle.crypto.tls.DatagramTransport

/**
 * [QueueDatagramTransport] is an implementation of [DatagramTransport] which
 * reads its incoming data from a thread-safe queue and writes its outgoing
 * data to another thread-safe queue.  Another entity should be putting
 * the incoming data onto the incoming queue and handling the data put
 * on the outgoing queue.
 */
class QueueDatagramTransport(
    private val receiveInput: (ByteArray, Int, Int, Int) -> Int,
    private val sendOutput: (ByteArray, Int, Int) -> Unit,
    private val mtu: Int = 1500
) : DatagramTransport {

    override fun receive(buf: ByteArray, off: Int, length: Int, waitMillis: Int): Int {
        return receiveInput(buf, off, length, waitMillis)
    }

    override fun send(buf: ByteArray, off: Int, length: Int) {
        sendOutput(buf, off, length)
    }

    /**
     * TODO: don't think we need to do anything special here
     */
    override fun close() {}

    /**
     * Receive limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getReceiveLimit(): Int = mtu - 20 - 8

    /**
     * Send limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getSendLimit(): Int = mtu - 84 - 8
}

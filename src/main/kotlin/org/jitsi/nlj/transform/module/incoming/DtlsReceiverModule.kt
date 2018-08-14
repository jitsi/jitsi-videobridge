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
package org.jitsi.nlj.transform.module.incoming

import org.bouncycastle.crypto.tls.DTLSTransport
import org.jitsi.nlj.transform.module.Module
import org.jitsi.rtp.DtlsProtocolPacket
import org.jitsi.rtp.Packet
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit


/**
 * [DtlsReceiverModule] bridges a chain of modules to a DTLS's transport
 * (used by the bouncycastle stack).
 */
class DtlsReceiverModule : Module("DTLS Receiver") {
    private val incomingQueue = LinkedBlockingQueue<Packet>()
    /**
     * The negotiated DTLS transport.  If this is set, this module will
     * attempt to read from it every time DTLS packets are received, so
     * that they are 'pulled through' the DTLS stack.  Packets read from
     * this transport will be passed on to the next packet in the chain
     * in the same context that [doProcessPackets] is called in.
     */
    var dtlsTransport: DTLSTransport? = null
    /**
     * A buffer we'll use to receive data from [dtlsTransport].
     */
    private val dtlsAppDataBuf = ByteBuffer.allocate(1500)
    /**
     * The packets received here will be all DTLS packets (handshake,
     * application data, everything).  They're added onto the incoming queue.
     * The DTLS stack's transport will call into the receive function below,
     * which is how the DTLS packets will get into the stack.  The stack will
     * expose the DTLS transport for sending and receiving application data.
     */
    override fun doProcessPackets(p: List<Packet>) {
//        println("BRIAN: dtls receiver module received packets")
        incomingQueue.addAll(p)
        // If dtlsTransport is not null, then that means that the DTLS handshake has
        // completed.  If it has completed, then the thread doing the connect is no
        // longer pulling packets through the DTLS stack, so we want to do it on our
        // own.  After we write data to the queue we'll read from dtlsTransport in
        // the same context to pull any application data packets through and then
        // pass them to the next module in the chain.
        var bytesReceived = 0
        val outPackets = mutableListOf<Packet>()
        do {
            bytesReceived = dtlsTransport?.receive(dtlsAppDataBuf.array(), 0, 1500, 1) ?: 0
            if (bytesReceived > 0) {
                outPackets.add(DtlsProtocolPacket(dtlsAppDataBuf.duplicate()))
            }
        } while (bytesReceived > 0)
        if (outPackets.isNotEmpty()) {
            println("BRIAN: received dtls application data!")
            next(outPackets)
        }
        // During the handshake phase, the thread which called 'connect' will
        // pull all the handshake packets through, but after that something
        // else will have to pull through any dtls application data packets
        // (e.g. sctp).  We want to avoid having to constantly poll the dtls
        // transport for data (since there won't be much traffic) so one
        // option would be to do/schedule a read whenever the incoming packet
        // thread (i.e. the one calling this method) has written packets to
        // the queue.  That means that this context (or something that calls
        // it) would have to have access to the dtls transport or a way to
        // invoke a read on it.
    }

    /**
     * Read a packet from [incomingQueue] and copy its contents into the given
     * buffer.  This API matches that of the DTLS stack's [DatagramTransport],
     * allowing us to bridge this module into the DTLS stack.
     */
    fun receive(buf: ByteArray, off: Int, length: Int, timeoutMs: Int): Int {
        val packet = incomingQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        System.arraycopy(packet.buf.array(), 0, buf, off, Math.min(length, packet.size))

        return packet.size
    }
}

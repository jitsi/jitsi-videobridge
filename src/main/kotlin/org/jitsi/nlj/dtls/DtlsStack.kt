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

import org.bouncycastle.crypto.tls.Certificate
import org.bouncycastle.crypto.tls.DTLSTransport
import org.bouncycastle.crypto.tls.DatagramTransport
import org.bouncycastle.crypto.tls.TlsContext
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.DtlsProtocolPacket
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.clone
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Represents a single instance of a DTLS stack for a given connection.  This class also acts as the [DatagramTransport]
 * used by the underlying DTLS library in order to send and receive DTLS packets.  Users of this class need to handle
 * passing incoming DTLS packets into the stack, as well as handling packets the stack wants to send out.  The passing
 * of incoming packets is done via calling [processIncomingDtlsPackets].  The handling of outgoing packets is done by
 * assigning a handler to the [onOutgoingProtocolData] member.  Incoming packets may be either control packets
 * (terminated by the stack itself) or app packets which have been sent over DTLS (SCTP packets, for example).  After
 * passing incoming packets to the stack via [processIncomingDtlsPackets], any app packets ready for further processing
 * will be returned.  Outgoing packets can be sent via [sendDtlsAppData].
 *
 * An example of passing incoming DTLS packets through the stack:
 *
 *  --> Recv 'dtlsPacket' from the network and pass it into the stack:
 *  val appPackets = dtlsStack.processIncomingDtlsPackets(listOf(dtlsPacket))
 *  if (appPackets.isNotEmpty()) {
 *    // Process the app packets
 *  }
 *
 *  An example of sending app packets out via the DTLS stack:
 *  dtlsStack.onOutgoingData = { outgoingDtlsPacket ->
 *    // Work to send the packets out
 *  }
 *  val dtlsAppPacket = ...
 *  dtlsStack.sendDtlsAppData(dtlsAppPacket)
 *
 */
abstract class DtlsStack : DatagramTransport {
    companion object {
        /**
         * Because generating the certificate can be expensive, we generate a single
         * one to be used everywhere which expires in 24 hours (when we'll generate
         * another one).
         */
        private var certificate: CertificateInfo = DtlsUtils.generateCertificateInfo()
        private val syncRoot: Any = Any()
        fun getCertificate(): CertificateInfo {
            synchronized(DtlsStack.syncRoot) {
                val expiration = Duration.ofDays(1).toMillis()
                if (DtlsStack.certificate.timestamp + expiration < System.currentTimeMillis()) {
                    DtlsStack.certificate = DtlsUtils.generateCertificateInfo()
                }
                return DtlsStack.certificate
            }
        }
    }

    protected val logger = getLogger(this.javaClass)

    val localFingerprint: String
        get() = DtlsStack.getCertificate().localFingerprint

    val localFingerprintHashFunction: String
        get() = DtlsStack.getCertificate().localFingerprintHashFunction

    /**
     * The remote fingerprints sent to us over the signaling path.
     */
    var remoteFingerprints: Map<String, String> = HashMap()

    /**
     * Checks that a specific [Certificate] matches the remote fingerprints sent to us over the signaling path.
     */
    protected fun verifyAndValidateRemoteCertificate(certificate: Certificate){
        DtlsUtils.verifyAndValidateCertificate(certificate, remoteFingerprints)
        // The above throws an exception if the checks fail.
        logger.cdebug { "Fingerprints verified." }
    }

    /**
     * Incoming DTLS packets received from the network are stored here via [processIncomingDtlsPackets].  They are read
     * by the underlying DTLS library via the [receive] method, which the library calls to receive incoming data.
     */
    private val incomingProtocolData = LinkedBlockingQueue<PacketInfo>()
    var onOutgoingProtocolData: (List<PacketInfo>) -> Unit = {}

    /**
     * The negotiated DTLS transport.  This is used to read and write DTLS app data.
     */
    protected var dtlsTransport: DTLSTransport? = null

    /**
     * A buffer we'll use to receive data from [dtlsTransport].
     */
    private val dtlsAppDataBuf = ByteBuffer.allocate(1500)

    fun onHandshakeComplete(handler: (TlsContext) -> Unit) {
        handshakeCompleteHandler = handler
    }
    protected var handshakeCompleteHandler: (TlsContext) -> Unit = {}

    /**
     * Process incoming DTLS packets from the network by passing them into the stack.  All received DTLS packets should
     * be sent to the stack via this method.  Returns any DTLS app packets which were processed by the stack as part
     * of this call.
     */
    fun processIncomingDtlsPackets(packetInfos: List<PacketInfo>): List<PacketInfo> {
        incomingProtocolData.addAll(packetInfos)
        var bytesReceived: Int
        val outPackets = mutableListOf<PacketInfo>()
        do {
            bytesReceived = dtlsTransport?.receive(dtlsAppDataBuf.array(), 0, 1500, 1) ?: -1
            if (bytesReceived > 0) {
                val bufCopy = dtlsAppDataBuf.clone();
                bufCopy.limit(bytesReceived)
                outPackets.add(PacketInfo(DtlsProtocolPacket(bufCopy)))
            }
        } while (bytesReceived > 0)
        return outPackets
    }

    fun sendDtlsAppData(packetInfo: PacketInfo) {
        val buf = packetInfo.packet.getBuffer()
        dtlsTransport?.send(buf.array(), buf.arrayOffset(), buf.limit())
    }

    override fun close() {}

    /**
     * Receive limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getReceiveLimit(): Int = 1500 - 20 - 8

    /**
     * Send limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getSendLimit(): Int = 1500 - 84 - 8

    override fun receive(buf: ByteArray, off: Int, length: Int, waitMillis: Int): Int {
        val packetInfo = incomingProtocolData.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS) ?: return -1
        val packet = packetInfo.packet
        System.arraycopy(packet.getBuffer().array(), 0, buf, off, Math.min(length, packet.sizeBytes))

        return packet.sizeBytes
    }

    /**
     * Send an outgoing DTLS packet (already processed by the DTLS stack) out via invoking
     * a handler.
     *
     * We have to use a synchronous callback approach here, as some packets originate
     * from within the stack itself (e.g. during connect) and if we put the packets in,
     * for example, a queue, we'd still have to fire some trigger for something to come
     * in and read them.
     */
    override fun send(buf: ByteArray, off: Int, length: Int) {
        val packet = PacketInfo(UnparsedPacket(ByteBuffer.wrap(buf, off, length)))
        onOutgoingProtocolData(listOf(packet))
    }

    abstract fun getChosenSrtpProtectionProfile(): Int
}

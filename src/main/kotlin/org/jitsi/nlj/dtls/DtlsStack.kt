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

package org.jitsi.nlj.dtls

import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * A DTLS stack implementation, which can act as either the DTLS server or DTLS client, and can be used
 * for negotiating the DTLS connection and sending and receiving data over that connection.
 *
 * This class does not directly communicate with the network in any way.  Raw DTLS packets must be fed into
 * this stack via the [processIncomingProtocolData] method.  [incomingDataHandler] will be invoked with the
 * decrypted, application data sent over the connection.
 *
 * Data can be sent through the DTLS connection via [sendApplicationData]; it will be encrypted by the stack and
 * then sent out via the [outgoingDataHandler], which must be set to have the data go anywhere interesting :)
 *
 * [eventHandler] will be invoked when any events occur.
 *
 * After wiring up all the handlers, to start a connection the stack must be told which role to fullfil: client
 * or server.  This can be done by calling either the [actAsClient] or [actAsServer] methods.  Once the role has
 * been set, [start] can be called to start the negotiation.
 */
class DtlsStack(
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)
    private val roleSet = CountDownLatch(1)

    /**
     * The certificate info for this particular [DtlsStack] instance. We save it in a local val because the global one
     * might be refreshed.
     */
    private val certificateInfo = DtlsStack.certificateInfo

    val localFingerprintHashFunction: String
        get() = certificateInfo.localFingerprintHashFunction

    val localFingerprint: String
        get() = certificateInfo.localFingerprint

    /**
     * The remote fingerprints sent to us over the signaling path.
     */
    var remoteFingerprints: Map<String, String> = HashMap()

    /**
     * A handler which will be invoked when DTLS application data is received
     */
    var incomingDataHandler: IncomingDataHandler? = null

    /**
     * The method [DtlsStack] will invoke when it wants to send DTLS data out onto the network.
     */
    var outgoingDataHandler: OutgoingDataHandler? = null

    /**
     * Handle to be invoked when events occur
     */
    var eventHandler: EventHandler? = null

    private var running: Boolean = false
    private val incomingProtocolData = java.util.ArrayDeque<ByteBuffer>(QUEUE_SIZE)
    /**
     * This lock is used to make access to [running] and [incomingProtocolData] atomic
     */
    private val lock = Any()
    private var numPacketDropsQueueFull = 0

    /**
     * The [DtlsRole] 'plugin' that will determine how this stack operates (as a client
     * or a server).  A call to [actAsClient] or [actAsServer] must be made to fill out
     * this role and successfully call [start]
     */
    var role: DtlsRole? = null
        private set

    /**
     * A buffer we'll use to receive data from [dtlsTransport].
     */
    private val dtlsAppDataBuf = ByteArray(1500)

    /**
     * The negotiated DTLS transport.  This is used to read and write DTLS application data.
     */
    private var dtlsTransport: DTLSTransport? = null

    /**
     * The [DatagramTransport] implementation we use for this stack.
     */
    private val datagramTransport: DatagramTransport = DatagramTransportImpl(logger)

    fun actAsServer() {
        role = DtlsServer(
            datagramTransport,
            certificateInfo,
            { chosenSrtpProfile, tlsRole, keyingMaterial ->
                eventHandler?.handshakeComplete(chosenSrtpProfile, tlsRole, keyingMaterial)
            },
            this::verifyAndValidateRemoteCertificate,
            logger
        )
        roleSet.countDown()
    }

    fun actAsClient() {
        role = DtlsClient(
            datagramTransport,
            certificateInfo,
            { chosenSrtpProfile, tlsRole, keyingMaterial ->
                eventHandler?.handshakeComplete(chosenSrtpProfile, tlsRole, keyingMaterial)
            },
            this::verifyAndValidateRemoteCertificate,
            logger
        )
        roleSet.countDown()
    }
    /**
     * 'start' this stack, in whatever role it has been told to operate (client or server).  If a role
     * has not yet been yet (via [actAsServer] or [actAsClient]), then it will block until the role
     * has been set.
     */
    fun start() {
        roleSet.await()
        synchronized(lock) {
            running = true
        }

        dtlsTransport = role?.start()
        // There is a bit of a race here: It's technically possible the
        // far side could finish the handshake and send a message before
        // this side assigns dtlsTransport here.  If so, that message
        // would be passed to #processIncomingProtocolData and put in
        // incomingProtocolData, but, since dtlsTransport won't be set
        // yet, we won't 'receive' it yet.  Check for any incoming packets
        // here, to handle this case.
        processIncomingProtocolData()
    }

    fun close() {
        datagramTransport.close()
        synchronized(lock) {
            running = false
            incomingProtocolData.forEach { buf ->
                BufferPool.returnBuffer(buf.array())
            }
            incomingProtocolData.clear()
        }
    }

    /**
     * Checks that a specific [Certificate] matches the remote fingerprints sent to us over the signaling path.
     */
    private fun verifyAndValidateRemoteCertificate(remoteCertificate: Certificate?) {
        remoteCertificate?.let {
            DtlsUtils.verifyAndValidateCertificate(it, remoteFingerprints)
            // The above throws an exception if the checks fail.
            logger.cdebug { "Fingerprints verified." }
        } ?: run {
            throw DtlsUtils.DtlsException("Remote certificate was null")
        }
    }

    fun sendApplicationData(data: ByteArray, off: Int, len: Int) {
        dtlsTransport?.send(data, off, len)
    }

    private fun processIncomingProtocolData() {
        var bytesReceived: Int
        do {
            val bufCopy2: ByteArray? = synchronized(dtlsAppDataBuf) {
                bytesReceived = dtlsTransport?.receive(dtlsAppDataBuf, 0, 1500, 1) ?: -1

                if (bytesReceived > 0) {
                    // Copy again to copy out of dtlsAppDataBuf, which we re-use.
                    BufferPool.getBuffer(bytesReceived).apply {
                        System.arraycopy(dtlsAppDataBuf, 0, this, 0, bytesReceived)
                    }
                } else {
                    null
                }
            }
            if (bufCopy2 != null) {
                incomingDataHandler?.dataReceived(bufCopy2, 0, bytesReceived)
            }
        } while (bytesReceived > 0)
    }

    /**
     * We get 'pushed' the data from a lower transport layer, but bouncycastle wants to 'pull' the data
     * itself.  To mimic this, we put the received data into a queue, and then 'pull' it through ourselves by
     * calling 'receive' on the negotiated [DTLSTransport].
     *
     * Note: the data we get here may be a DTLS protocol packet and therefore won't generate anything
     * to be received by [dtlsTransport] or a DTLS app packet (data sent over DTLS after the handshake has
     * completed) and will result in data being received through [dtlsTransport].  It's possible, though,
     * that the handshake has finished and the far end has sent application data but we've not yet set
     * [dtlsTransport], so we "miss" it.  We don't lose this data, but it will sit inside of [incomingProtocolData]
     * until the next packet comes through.  This means that we must make a copy of the buffer we receive, as we
     * won't necessarily be done with it by the time this method completes.
     */
    fun processIncomingProtocolData(data: ByteArray, off: Int, len: Int) {
        val bufCopy = BufferPool.getBuffer(len).apply {
            System.arraycopy(data, off, this, 0, len)
        }
        synchronized(lock) {
            if (!running) {
                BufferPool.returnBuffer(bufCopy)
                return
            }
            if (incomingProtocolData.size >= QUEUE_SIZE) {
                logger.warn("DTLS stack queue full, dropping packet")
                BufferPool.returnBuffer(bufCopy)
                numPacketDropsQueueFull++
                Unit
            } else {
                incomingProtocolData.add(ByteBuffer.wrap(bufCopy, 0, len))
            }
        }

        processIncomingProtocolData()
    }

    fun getDebugState(): OrderedJsonObject = OrderedJsonObject().apply {
        put("localFingerprintHashFunction", certificateInfo.localFingerprint)
        put("remoteFingerprints", remoteFingerprints.map { (hash, fp) -> "$hash: $fp" }.joinToString())
        put("role", (role?.javaClass ?: "null").toString())
        put("num_packet_drops_queue_full", numPacketDropsQueueFull)
    }

    companion object {
        private const val QUEUE_SIZE = 50
        /**
         * Because generating the certificateInfo can be expensive, we generate a single
         * one to be used everywhere which expires in 24 hours (when we'll generate
         * another one).
         */
        private val syncRoot: Any = Any()
        private var certificateInfo: CertificateInfo = DtlsUtils.generateCertificateInfo()
            get() = synchronized(syncRoot) {
                val expirationPeriodMs = Duration.ofDays(1).toMillis()
                if (field.creationTimestampMs + expirationPeriodMs < System.currentTimeMillis()) {
                    // TODO: avoid creating our own thread
                    thread { field = DtlsUtils.generateCertificateInfo() }
                }
                return field
            }
    }

    /**
     * An implementation of [DatagramTransport] which 'receives' from the queue in [DtlsStack] and sends out
     * via [DtlsStack]'s [outgoingDataHandler]
     */
    inner class DatagramTransportImpl(parentLogger: Logger) : DatagramTransport {
        private val logger = createChildLogger(parentLogger)

        override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
            val data = synchronized(lock) {
                if (!running || incomingProtocolData.isEmpty()) {
                    return -1
                }
                // Note: we don't use the timeout values here because we don't actually need them.  We add a buffer
                // into this queue above and then call a method which will pull it through via this method.  The
                // only reason waitMillis exists is because the BouncyCastle DatagramTransport interface this class
                // implements defines it that way.
                incomingProtocolData.removeFirst()
            }
            val length = min(len, data.limit())
            if (length < data.limit()) {
                logger.warn(
                    "Passed buffer size ($len) was too small to hold incoming data size (${data.limit()}); " +
                        "data was truncated"
                )
            }
            System.arraycopy(data.array(), data.arrayOffset(), buf, off, length)
            BufferPool.returnBuffer(data.array())
            return length
        }

        override fun send(buf: ByteArray, off: Int, len: Int) {
            outgoingDataHandler?.sendData(buf, off, len)
        }

        /**
         * Receive limit computation copied from [org.bouncycastle.tls.UDPTransport]
         */
        override fun getReceiveLimit(): Int = 1500 - 20 - 8

        /**
         * Send limit computation copied from [org.bouncycastle.tls.UDPTransport]
         */
        override fun getSendLimit(): Int = 1500 - 84 - 8

        override fun close() {}
    }

    interface IncomingDataHandler {
        /**
         * Notify the handler that data has been received.  The handler takes ownership of the passed
         * buffer, and it should be returned to the buffer pool when done with it.
         */
        fun dataReceived(data: ByteArray, off: Int, len: Int)
    }

    interface OutgoingDataHandler {
        fun sendData(data: ByteArray, off: Int, len: Int)
    }

    interface EventHandler {
        fun handshakeComplete(chosenSrtpProtectionProfile: Int, tlsRole: TlsRole, keyingMaterial: ByteArray)
    }
}

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

package org.jitsi.videobridge.transport.dtls

import org.jitsi.nlj.dtls.DtlsClient
import org.jitsi.nlj.dtls.DtlsServer
import org.jitsi.nlj.dtls.DtlsStack
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport layer which negotiates a DTLS connection and supports
 * decrypting and encrypting data.
 *
 * Incoming DTLS data should be fed into this layer via [dtlsDataReceived],
 * and decrypted DTLS application data will be passed to the
 * [incomingDataHandler], which should be set by an interested party.
 *
 * Outgoing data can be sent via [sendDtlsData] and the encrypted data will
 * be passed to the [outgoingDataHandler], which should be set by an
 * interested party.
 */
class DtlsTransport(parentLogger: Logger) {
    private val logger = createChildLogger(parentLogger)

    private val running = AtomicBoolean(true)
    @JvmField
    var incomingDataHandler: IncomingDataHandler? = null
    @JvmField
    var outgoingDataHandler: OutgoingDataHandler? = null
    @JvmField
    var eventHandler: EventHandler? = null
    private var dtlsHandshakeComplete = false

    val isConnected: Boolean
        get() = dtlsHandshakeComplete

    private val stats = Stats()

    /**
     * The DTLS stack instance
     */
    private val dtlsStack = DtlsStack(logger).also {
        // Install a handler for when the DTLS stack has decrypted application data available
        it.incomingDataHandler = object : DtlsStack.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, off: Int, len: Int) {
                stats.numPacketsReceived++
                incomingDataHandler?.dtlsAppDataReceived(data, off, len) ?: run {
                    stats.numIncomingPacketsDroppedNoHandler++
                }
            }
        }

        // Install a handler to allow the DTLS stack to send out encrypted data
        it.outgoingDataHandler = object : DtlsStack.OutgoingDataHandler {
            override fun sendData(data: ByteArray, off: Int, len: Int) {
                outgoingDataHandler?.sendData(data, off, len)?.also {
                    stats.numPacketsSent++
                } ?: run {
                    stats.numOutgoingPacketsDroppedNoHandler++
                }
            }
        }

        // Handle DTLS stack events
        it.eventHandler = object : DtlsStack.EventHandler {
            override fun handshakeComplete(
                chosenSrtpProtectionProfile: Int,
                tlsRole: TlsRole,
                keyingMaterial: ByteArray
            ) {
                dtlsHandshakeComplete = true
                eventHandler?.handshakeComplete(chosenSrtpProtectionProfile, tlsRole, keyingMaterial)
            }
        }
    }

    /**
     * Start a DTLS handshake.  The 'role' should have been set before calling this
     * (via [setSetupAttribute]
     */
    fun startDtlsHandshake() {
        logger.info("Starting DTLS handshake, role=${dtlsStack.role}")
        if (dtlsStack.role == null) {
            logger.warn("Starting the DTLS stack before it knows its role")
        }
        try {
            dtlsStack.start()
        } catch (t: Throwable) {
            // TODO: we're not doing anything here, should we? or change the log?
            logger.error("Error during DTLS negotiation, closing this transport manager", t)
        }
    }

    fun setSetupAttribute(setupAttr: String?) {
        if (setupAttr.isNullOrEmpty()) {
            return
        }
        when (setupAttr.lowercase()) {
            "active" -> {
                logger.info("The remote side is acting as DTLS client, we'll act as server")
                dtlsStack.actAsServer()
            }
            "passive" -> {
                logger.info("The remote side is acting as DTLS server, we'll act as client")
                dtlsStack.actAsClient()
            }
            else -> {
                logger.error(
                    "The remote side sent an unrecognized DTLS setup value: " +
                        setupAttr
                )
            }
        }
    }

    fun setRemoteFingerprints(remoteFingerprints: Map<String, String>) {
        // Don't pass an empty list to the stack in order to avoid wiping
        // certificates that were contained in a previous request.
        if (remoteFingerprints.isEmpty()) {
            return
        }

        dtlsStack.remoteFingerprints = remoteFingerprints
        val hasSha1Hash = remoteFingerprints.keys.any { it.equals("sha-1", ignoreCase = true) }
        if (dtlsStack.role == null && hasSha1Hash) {
            // hack(george) Jigasi sends a sha-1 dtls fingerprint without a
            // setup attribute and it assumes a server role for the bridge.

            logger.info("Assume that the remote side is Jigasi, we'll act as server")
            dtlsStack.actAsServer()
        }
    }

    /**
     * Describe the properties of this [DtlsTransport] into the given
     * [IceUdpTransportPacketExtension]
     */
    fun describe(iceUdpTransportPe: IceUdpTransportPacketExtension) {
        val fingerprintPE = iceUdpTransportPe.getFirstChildOfType(DtlsFingerprintPacketExtension::class.java) ?: run {
            DtlsFingerprintPacketExtension().also { iceUdpTransportPe.addChildExtension(it) }
        }
        fingerprintPE.setup = when (dtlsStack.role) {
            is DtlsServer -> "passive"
            is DtlsClient -> "active"
            null -> "actpass"
            else -> throw IllegalStateException("Cannot describe role ${dtlsStack.role}")
        }
        fingerprintPE.fingerprint = dtlsStack.localFingerprint
        fingerprintPE.hash = dtlsStack.localFingerprintHashFunction
    }

    /**
     * Notify this layer that DTLS data has been received from the network
     */
    fun dtlsDataReceived(data: ByteArray, off: Int, len: Int) =
        dtlsStack.processIncomingProtocolData(data, off, len)

    /**
     * Send out DTLS data
     */
    fun sendDtlsData(data: ByteArray, off: Int, len: Int) =
        dtlsStack.sendApplicationData(data, off, len)

    fun stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping")
            dtlsStack.close()
        }
    }

    fun getDebugState(): OrderedJsonObject = stats.toJson().apply {
        put("running", running.get())
        put("is_connected", isConnected)
    }

    private data class Stats(
        var numPacketsReceived: Int = 0,
        var numIncomingPacketsDroppedNoHandler: Int = 0,
        var numPacketsSent: Int = 0,
        var numOutgoingPacketsDroppedNoHandler: Int = 0
    ) {
        fun toJson(): OrderedJsonObject = OrderedJsonObject().apply {
            put("num_packets_received", numPacketsReceived)
            put("num_incoming_packets_dropped_no_handler", numIncomingPacketsDroppedNoHandler)
            put("num_packets_sent", numPacketsSent)
            put("num_outgoing_packets_dropped_no_handler", numOutgoingPacketsDroppedNoHandler)
        }
    }

    /**
     * A handler for when [DtlsTransport] wants to send data out
     * onto the network
     */
    interface OutgoingDataHandler {
        fun sendData(buf: ByteArray, off: Int, len: Int)
    }

    /**
     * A handler for when [DtlsTransport] has received DTLS application
     * data
     */
    interface IncomingDataHandler {
        fun dtlsAppDataReceived(buf: ByteArray, off: Int, len: Int)
    }

    /**
     * A handler for [DtlsTransport] events
     */
    interface EventHandler {
        fun handshakeComplete(chosenSrtpProtectionProfile: Int, tlsRole: TlsRole, keyingMaterial: ByteArray)
    }
}

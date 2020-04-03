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
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import java.util.concurrent.atomic.AtomicBoolean

class DtlsTransportk(
    parentLogger: Logger
) {
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

    private val dtlsStack = DtlsStack(logger).also {
        it.incomingDataHandler = object : DtlsStack.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, off: Int, len: Int) {
                stats.numPacketsReceived++
                incomingDataHandler?.dtlsAppDataReceived(data, off, len) ?: run {
                    stats.numIncomingPacketsDroppedNoHandler++
                }
            }
        }

        it.outgoingDataHandler = object : DtlsStack.OutgoingDataHandler {
            override fun sendData(data: ByteArray, off: Int, len: Int) {
                outgoingDataHandler?.let { outgoingDataHandler ->
                    outgoingDataHandler.sendData(data, off, len)
                    stats.numPacketsSent++
                } ?: run { stats.numOutgoingPacketsDroppedNoHandler++ }
            }
        }

        it.eventHandler = object : DtlsStack.EventHandler {
            override fun handshakeComplete(chosenSrtpProtectionProfile: Int, tlsRole: TlsRole, keyingMaterial: ByteArray) {
                eventHandler?.handshakeComplete(chosenSrtpProtectionProfile, tlsRole, keyingMaterial)
            }
        }
    }

    fun setSetupAttribute(setupAttr: String?) {
        if (setupAttr.isNullOrEmpty()) {
            return
        }
        when (setupAttr.toLowerCase()) {
            "active" -> {
                logger.info("The remote side is acting as DTLS client, we'll act as server")
                dtlsStack.actAsServer()
            }
            "passive" -> {
                logger.info("The remote side is acting as DTLS server, we'll act as client")
                dtlsStack.actAsClient()
            }
            else -> {
                logger.error("The remote side sent an unrecognized DTLS setup value: " +
                        setupAttr)
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

    fun startDtlsHandshake() {
        logger.info("Starting DTLS handshake")
        if (dtlsStack.role == null) {
            logger.warn("Staring the DTLS stack before it knows its role")
        }
        try {
            dtlsStack.start()
        } catch (t: Throwable) {
            // TODO: we're not doing anything here, should we? or change the log?
            logger.error("Error during DTLS negotiation, closing this transport manager", t)
        }
    }

    fun describe(iceUdpTransportPacketExtension: IceUdpTransportPacketExtension) {
        val fingerprintPE = iceUdpTransportPacketExtension.getFirstChildOfType(DtlsFingerprintPacketExtension::class.java) ?: run {
            DtlsFingerprintPacketExtension().also { iceUdpTransportPacketExtension.addChildExtension(it) }
        }
        fingerprintPE.setup = when (dtlsStack.role) {
            is DtlsServer -> "passive"
            is DtlsClient -> "active"
            null -> "actpass"
            else -> throw IllegalStateException("Cannot describe role ${dtlsStack.role}")
        }
    }

    fun dtlsDataReceived(data: ByteArray, off: Int, len: Int) =
        dtlsStack.processIncomingProtocolData(data, off, len)

    fun sendDtlsData(data: ByteArray, off: Int, len: Int) =
        dtlsStack.sendApplicationData(data, off, len)

    fun stop() {
        if (running.compareAndSet(true, false)) {
            // Should we be doing some cleanup here?
        }
    }

    fun getDebugState(): OrderedJsonObject = stats.toJson()

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
     * A handler for when [DtlsTransportk] wants to send data out
     * onto the network
     */
    interface OutgoingDataHandler {
        fun sendData(buf: ByteArray, off: Int, len: Int)
    }

    /**
     * A handler for when [DtlsTransportk] has received DTLS application
     * data
     */
    interface IncomingDataHandler {
        fun dtlsAppDataReceived(buf: ByteArray, off: Int, len: Int)
    }

    interface EventHandler {
        fun handshakeComplete(chosenSrtpProtectionProfile: Int, tlsRole: TlsRole, keyingMaterial: ByteArray)
    }
}

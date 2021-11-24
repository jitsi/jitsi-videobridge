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
package org.jitsi.videobridge

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.videobridge.transport.dtls.DtlsTransport
import org.jitsi.videobridge.transport.ice.IceTransport
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.util.looksLikeDtls
import org.jitsi.videobridge.websocket.colibriWebSocketServiceSupplier
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import java.time.Clock
import java.time.Instant

/**
 * Models a relay (remote videobridge) in a [Conference].
 */
/* TODO: figure out how best to share code between this and [Endpoint], without multiple inheritance. */
class Relay @JvmOverloads constructor(
    /**
     * The unique identifier of this [Relay]
     */
    val id: String,
    /**
     * The [Conference] this [Relay] belongs to.
     */
    val conference: Conference,
    parentLogger: Logger,
    /**
     * True if the ICE agent for this [Relay] will be initialized to serve
     * as a controlling ICE agent, false otherwise
     */
    iceControlling: Boolean,
    private val clock: Clock = Clock.systemUTC()
) : EncodingsManager.EncodingsUpdateListener {

    private val eventEmitter: EventEmitter<AbstractEndpoint.EventHandler> = SyncEventEmitter()

    /**
     * The [Logger] used by the [Relay] class to print debug
     * information.
     */
    private val logger: Logger

    init {
        val context = HashMap<String, String>()
        context["relayId"] = id
        logger = parentLogger.createChildLogger(this.javaClass.name, context)
    }

    private val iceTransport = IceTransport(id, iceControlling, logger)
    private val dtlsTransport = DtlsTransport(logger)

    init {
        conference.encodingsManager.subscribe(this)
        setupIceTransport()
        setupDtlsTransport()

        conference.videobridge.statistics.totalRelays.incrementAndGet()
    }

    private fun setupIceTransport() {
        iceTransport.incomingDataHandler = object : IceTransport.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, offset: Int, length: Int, receivedTime: Instant) {
                // DTLS data will be handled by the DtlsTransport, but SRTP data can go
                // straight to the transceiver
                if (looksLikeDtls(data, offset, length)) {
                    // DTLS transport is responsible for making its own copy, because it will manage its own
                    // buffers
                    dtlsTransport.dtlsDataReceived(data, offset, length)
                } else {
                    val copy = ByteBufferPool.getBuffer(
                        length +
                            RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                            Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                    )
                    System.arraycopy(data, offset, copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length)
                    val pktInfo =
                        PacketInfo(UnparsedPacket(copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length)).apply {
                            this.receivedTime = receivedTime.toEpochMilli()
                        }
                    // TODO: transceiver.handleIncomingPacket(pktInfo)
                }
            }
        }
        iceTransport.eventHandler = object : IceTransport.EventHandler {
            override fun connected() {
                logger.info("ICE connected")
                eventEmitter.fireEvent { iceSucceeded() }
                /* TODO: transceiver.setOutgoingPacketHandler(object : PacketHandler {
                    override fun processPacket(packetInfo: PacketInfo) {
                        outgoingSrtpPacketQueue.add(packetInfo)
                    }
                }) */
                TaskPools.IO_POOL.execute(iceTransport::startReadingData)
                TaskPools.IO_POOL.execute(dtlsTransport::startDtlsHandshake)
            }

            override fun failed() {
                eventEmitter.fireEvent { iceFailed() }
            }

            override fun consentUpdated(time: Instant) {
                // TODO transceiver.packetIOActivity.lastIceActivityInstant = time
            }
        }
    }

    private fun setupDtlsTransport() {
        dtlsTransport.incomingDataHandler = object : DtlsTransport.IncomingDataHandler {
            override fun dtlsAppDataReceived(buf: ByteArray, off: Int, len: Int) {
                // TODO this@Relay.dtlsAppPacketReceived(buf, off, len)
            }
        }
        dtlsTransport.outgoingDataHandler = object : DtlsTransport.OutgoingDataHandler {
            override fun sendData(buf: ByteArray, off: Int, len: Int) {
                iceTransport.send(buf, off, len)
            }
        }
        dtlsTransport.eventHandler = object : DtlsTransport.EventHandler {
            override fun handshakeComplete(
                chosenSrtpProtectionProfile: Int,
                tlsRole: TlsRole,
                keyingMaterial: ByteArray
            ) {
                logger.info("DTLS handshake complete")
                // TODO transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial)
                // TODO scheduleEndpointMessageTransportTimeout()
            }
        }
    }

    /**
     * Sets the remote transport information (ICE candidates, DTLS fingerprints).
     *
     * @param transportInfo the XML extension which contains the remote
     * transport information.
     */
    fun setTransportInfo(transportInfo: IceUdpTransportPacketExtension) {
        val remoteFingerprints = mutableMapOf<String, String>()
        val fingerprintExtensions = transportInfo.getChildExtensionsOfType(DtlsFingerprintPacketExtension::class.java)
        fingerprintExtensions.forEach { fingerprintExtension ->
            if (fingerprintExtension.hash != null && fingerprintExtension.fingerprint != null) {
                remoteFingerprints[fingerprintExtension.hash] = fingerprintExtension.fingerprint
            } else {
                logger.info("Ignoring empty DtlsFingerprint extension: ${transportInfo.toXML()}")
            }
        }
        dtlsTransport.setRemoteFingerprints(remoteFingerprints)
        if (fingerprintExtensions.isNotEmpty()) {
            val setup = fingerprintExtensions.first().setup
            dtlsTransport.setSetupAttribute(setup)
        }
        iceTransport.startConnectivityEstablishment(transportInfo)
    }

    fun describeTransport(): IceUdpTransportPacketExtension {
        val iceUdpTransportPacketExtension = IceUdpTransportPacketExtension()
        iceTransport.describe(iceUdpTransportPacketExtension)
        dtlsTransport.describe(iceUdpTransportPacketExtension)
        /* TODO this is wrong */
        colibriWebSocketServiceSupplier.get()?.let { colibriWebsocketService ->
            colibriWebsocketService.getColibriWebSocketUrl(
                conference.id,
                id,
                iceTransport.icePassword
            )?.let { wsUrl ->
                val wsPacketExtension = WebSocketPacketExtension(wsUrl)
                iceUdpTransportPacketExtension.addChildExtension(wsPacketExtension)
            }
        }

        logger.cdebug { "Transport description:\n${iceUdpTransportPacketExtension.toXML()}" }

        return iceUdpTransportPacketExtension
    }

    override fun onNewSsrcAssociation(
        endpointId: String?,
        primarySsrc: Long,
        secondarySsrc: Long,
        type: SsrcAssociationType?
    ) {
        TODO("Not yet implemented")
    }

    fun expire() {
        logger.info("Expiring.")
        conference.relayExpired(this)

        try {
            /* TODO
            updateStatsOnExpire()
            transceiver.stop()
            logger.cdebug { transceiver.getNodeStats().prettyPrint(0) }
            logger.cdebug { bitrateController.debugState.toJSONString() }
            */
            logger.cdebug { iceTransport.getDebugState().toJSONString() }
            logger.cdebug { dtlsTransport.getDebugState().toJSONString() }

            /* TODO logger.info("Spent ${bitrateController.getTotalOversendingTime().seconds} seconds oversending")

            transceiver.teardown()
            _messageTransport.close()
            sctpHandler.stop()
            sctpManager?.closeConnection() */
        } catch (t: Throwable) {
            logger.error("Exception while expiring: ", t)
        }

        // bandwidthProbing.enabled = false
        // recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing)
        conference.encodingsManager.unsubscribe(this)

        dtlsTransport.stop()
        iceTransport.stop()
        // outgoingSrtpPacketQueue.close()

        logger.info("Expired.")
    }
}

package org.jitsi.videobridge.export

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.colibri2.FeatureNotImplementedException
import org.jitsi.videobridge.exporter.MediaJsonEncoder
import org.jitsi.videobridge.recorder.MediaJsonRecorder
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.xmpp.extensions.colibri2.Export

class Exporter : PotentialPacketHandler {
    val logger = createLogger()
    var started = false

    private val encoder = MediaJsonEncoder { recorder.handleEvent(it) }
    private val recorder = MediaJsonRecorder()

    fun setExports(exports: List<Export>) {
        when {
            started && exports.isNotEmpty() -> throw FeatureNotImplementedException("Changing exports once enabled.")
            exports.isEmpty() -> stop()
            exports.size > 1 -> throw FeatureNotImplementedException("Multiple exports")
            exports[0].video -> throw FeatureNotImplementedException("Video")
            else -> start(exports[0])

        }
    }

    override fun wants(packet: PacketInfo): Boolean = started && packet.packet is AudioRtpPacket

    override fun send(packet: PacketInfo) {
        if (started) {
            encoder.encode(packet.packetAs(), packet.endpointId!!)
        }
        ByteBufferPool.returnBuffer(packet.packet.buffer)
    }

    fun stop() {
        started = false
        logger.info("Stopping.")
    }

    fun start(export: Export) {
        logger.info("Starting with url=${export.url}")
        started = true
    }
}
/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.transform.node

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class CompliancePcapWriter(
    private val logger: Logger,
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    private val id: String,
    private val captureEnd: String
) {
    private var writer: PcapWriter? = null
    private val lock = Any()
    private var contextId: String? = null
    private var mode: String = CAP_MODE_NONE

    private var captureAudio = false
    private var captureVideo = false

    fun capId(): String {
        return "${id}_$captureEnd"
    }

    fun filename(): String {
        return "$basePath/${contextId}_${capId()}"
    }

    fun pcapFilename(): String {
        return "${filename()}.pcap"
    }

    fun jsonFilename(): String {
        return "${filename()}.json"
    }

    fun validJmtConfig(): Boolean {
        if (!allowed) {
            logger.info("Compliance recording:${capId()} is not allowed in jmt.compliance-recording.enabled")
            return false
        }

        if (basePath.isEmpty()) {
            logger.error("Compliance recording:${capId()} jmt.compliance-recording.base-path is not configured")
            return false
        }

        if (!Files.isDirectory(Paths.get(basePath))) {
            logger.error("Compliance recording:${capId()} base-path:$basePath is not a valid directory path")
            return false
        }

        return true
    }

    fun writeMetadata(): Boolean {
        val meta = JSONObject()

        meta.put("endpoint_id", id)
        meta.put("context_id", contextId)
        meta.put("capture_end", captureEnd)
        meta.put("capture_mode", mode)

        val payloadsMap = JSONArray()
        streamInformationStore.rtpPayloadTypes.forEach {
            if ((it.value.mediaType == MediaType.AUDIO && (mode == CAP_MODE_AUDIO || mode == CAP_MODE_AUDIO_VIDEO)) ||
                (it.value.mediaType == MediaType.VIDEO && (mode == CAP_MODE_VIDEO || mode == CAP_MODE_AUDIO_VIDEO))
            ) {
                val entry = JSONObject()
                entry["payload_type"] = it.value.pt
                entry["encoding"] = it.value.encoding.toString().lowercase()
                entry["media_type"] = it.value.mediaType.name.lowercase()
                entry["clock_rate"] = it.value.clockRate
                payloadsMap.add(entry)
            }
        }

        meta.put("payload_map", payloadsMap)

        // json-simple produces one long flat line
        // File(jsonFilename()).writeText(meta.toJSONString())

        // gson produces elements on separate lines
        val gson = GsonBuilder().setPrettyPrinting().create()
        val je: JsonElement = JsonParser.parseString(meta.toJSONString())

        try {
            File(jsonFilename()).writeText(gson.toJson(je))
        } catch (ex: Exception) {
            logger.error("Compliance recording:${capId()} exception: $ex")
            return false
        }

        return true
    }

    fun configure(newMode: String?, newContextId: String?) {

        // TODO - how to trace long line exceeding 120 chars
        logger.info("Compliance recording:${capId()} configure mode:$mode -> $newMode")
        logger.info("Compliance recording:${capId()} contextId:$contextId -> $newContextId")

        // Disable conditions
        if (newMode.isNullOrEmpty() || newMode == CAP_MODE_NONE) {
            disable()
            synchronized(lock) {
                mode = "none"
                contextId = null
            }
            return
        }

        // Checks for valid enable conditions
        if (!validJmtConfig()) {
            return
        }

        if (newMode !in listOf(CAP_MODE_AUDIO, CAP_MODE_VIDEO, CAP_MODE_AUDIO_VIDEO)) {
            logger.error("Compliance recording:${capId()} invalid mode:$newMode")
            return
        }

        if (newContextId.isNullOrEmpty()) {
            logger.error("Compliance recording:${capId()} invalid context-id:$newContextId")
            return
        }

        synchronized(lock) {
            if (mode == newMode && contextId == newContextId) {
                // nothing to do
                logger.warn("Compliance recording:${capId()} duplicate configuration request ignored.")
                return
            }
        }

        if (isEnabled()) {
            // reconfiguring to different mode on the fly?
            logger.warn("Compliance recording:${capId()} is already enabled - resetting by disabling first.")
            disable()
        }

        synchronized(lock) {
            mode = newMode
            contextId = newContextId
            if (!writeMetadata()) {
                contextId = null
                mode = "none"
                return
            }
        }

        // Enable / open the writer with the new mode and context-id
        enable()
    }

    fun enable() {
        logger.info("Compliance recording:${capId()} enable ${pcapFilename()}")

        if (!validJmtConfig()) {
            return
        }

        if (contextId.isNullOrEmpty()) {
            logger.error("Compliance recording:${capId()} is not configured with context-id")
            return
        }

        synchronized(lock) {
            captureAudio = false
            captureVideo = false
            when (mode) {
                CAP_MODE_AUDIO -> captureAudio = true
                CAP_MODE_VIDEO -> captureVideo = true
                CAP_MODE_AUDIO_VIDEO -> {
                    captureAudio = true
                    captureVideo = true
                }
                else -> {
                    logger.error("Compliance recording:${capId()} invalid mode:$mode")
                    return
                }
            }

            if (writer == null) {
                logger.info("Compliance recording:${capId()} enable ${pcapFilename()}")
                writer = PcapWriter(logger, pcapFilename())
            }
        }
    }

    fun disable() {
        synchronized(lock) {
            captureAudio = false
            captureVideo = false

            if (writer != null) {
                logger.info("Compliance recording:${capId()} disable ${pcapFilename()}")
                writer?.close()
                writer = null
            }
        }
    }

    fun isEnabled(): Boolean = writer != null

    fun newObserverNode(): Node = PcapWriterNode("Compliance recording:${capId()} pcap writer")

    private inner class PcapWriterNode(name: String) : ObserverNode(name) {
        override fun observe(packetInfo: PacketInfo) {
            synchronized(lock) {
                if (packetInfo.packet is AudioRtpPacket) {
                    if (captureAudio) {
                        writer?.processPacket(packetInfo)
                    }
                } else if (packetInfo.packet is VideoRtpPacket) {
                    if (captureVideo) {
                        writer?.processPacket(packetInfo)
                    }
                }
            }
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }

    companion object {
        private val allowed: Boolean by config("jmt.compliance-recording.enabled".from(JitsiConfig.newConfig))
        private val basePath: String by config("jmt.compliance-recording.base-path".from(JitsiConfig.newConfig))

        private const val CAP_MODE_NONE = "none"
        private const val CAP_MODE_AUDIO = "audio"
        private const val CAP_MODE_VIDEO = "video"
        private const val CAP_MODE_AUDIO_VIDEO = "audio-video"
    }
}

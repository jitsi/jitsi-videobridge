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
package org.jitsi.nlj

import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.ArrayUtils
import java.util.Collections
import java.util.NavigableMap
import java.util.TreeMap

/**
 * Represents a collection of [RtpLayerDesc]s that encode the same
 * media source. This specific implementation provides webrtc simulcast stream
 * suspension detection.
 *
 * We take the definition of "Media Source" from RFC 7656.  It takes a single
 * logical source of media, which might be represented by multiple encoded streams.
 *
 * @author George Politis
 */
class MediaSourceDesc
@JvmOverloads constructor(
    /**
     * The [RtpEncodingDesc]s that this [MediaSourceDesc]
     * possesses, ordered by their subjective quality from low to high.
     */
    val rtpEncodings: Array<RtpEncodingDesc>,
    /**
     * A string which identifies the owner of this source (e.g. the endpoint
     * which is the sender of the source).
     */
    val owner: String,
    /**
     * A string which identifies this source.
     */
    val sourceName: String,
    /**
     * The {@link VideoType} signaled for this media source (defaulting to {@code CAMERA} if nothing has been signaled).
     */
    var videoType: VideoType = VideoType.CAMERA,
) {
    /**
     * Current single-list view of all the encodings' layers.
     */
    private lateinit var layers: List<RtpLayerDesc>

    /**
     * Allow the lookup of a layer by the encoding id of a received packet.
     */
    private val layersById: MutableMap<Long, RtpLayerDesc> = HashMap()

    /**
     * Allow the lookup of a layer by index.
     */
    private val layersByIndex: NavigableMap<Int, RtpLayerDesc> = TreeMap()

    /**
     * Get a view of the source's RTP layers, in quality order.
     */
    val rtpLayers: List<RtpLayerDesc>
        @Synchronized
        get() = layers

    /**
     * Update the layer cache.  Should be synchronized on [this].
     */
    private fun updateLayerCache() {
        layersById.clear()
        layersByIndex.clear()
        val tempLayers = ArrayList<RtpLayerDesc>()

        for (encoding in rtpEncodings) {
            for (layer in encoding.layers) {
                layersById[encoding.encodingId(layer)] = layer
                layersByIndex[layer.index] = layer
                tempLayers.add(layer)
            }
        }
        layers = Collections.unmodifiableList(tempLayers)
    }

    init {
        updateLayerCache()
    }

    /**
     * Gets the last "stable" bitrate (in bps) of the encoding of the specified
     * index. The "stable" bitrate is measured on every new frame and with a
     * 5000ms window.
     *
     * If the bitrate for the specified index is 0, return bitrate of the highest-
     * index layer less than the index with a non-zero bitrate.
     *
     * @return the last "stable" bitrate (bps) of the encoding with a non-zero rate
     * at or below the specified index.
     */
    @Synchronized
    fun getBitrate(nowMs: Long, idx: Int): Bandwidth {
        for (entry in layersByIndex.headMap(idx, true).descendingMap()) {
            val bitrate = entry.value.getBitrate(nowMs)
            if (bitrate.bps > 0) {
                return bitrate
            }
        }
        return 0.bps
    }

    @Synchronized
    fun hasRtpLayers(): Boolean = layers.isNotEmpty()

    @Synchronized
    fun numRtpLayers(): Int = layersByIndex.size

    val primarySSRC: Long
        get() = rtpEncodings[0].primarySSRC

    @Synchronized
    fun getRtpLayerByQualityIdx(idx: Int): RtpLayerDesc? = layersByIndex[idx]

    @Synchronized
    fun findRtpLayerDescs(videoRtpPacket: VideoRtpPacket): Collection<RtpLayerDesc> {
        if (ArrayUtils.isNullOrEmpty(rtpEncodings)) {
            return emptyList()
        }
        return videoRtpPacket.getEncodingIds().mapNotNull { layersById[it] }
    }

    @Synchronized
    fun findRtpEncodingDesc(ssrc: Long): RtpEncodingDesc? = rtpEncodings.find { it.hasSsrc(ssrc) }

    @Synchronized
    fun getEncodingLayers(ssrc: Long): Array<RtpLayerDesc> {
        val enc = findRtpEncodingDesc(ssrc) ?: return emptyArray()
        return Array(enc.layers.size) { i ->
            enc.layers[i].copy()
        }
    }

    @Synchronized
    fun setEncodingLayers(layers: Array<RtpLayerDesc>, ssrc: Long) {
        val enc = findRtpEncodingDesc(ssrc) ?: return
        enc.layers = layers
        updateLayerCache()
    }

    /**
     * Clone an existing media source desc, inheriting layer descs' statistics.
     */
    @Synchronized
    fun copy() = MediaSourceDesc(
        Array(this.rtpEncodings.size) { i -> this.rtpEncodings[i].copy() },
        this.owner,
        this.sourceName,
        this.videoType
    )

    override fun toString(): String = "MediaSourceDesc[name=$sourceName owner=$owner, videoType=$videoType, " +
        "encodings=${rtpEncodings.joinToString(",")}]"

    /**
     * Checks whether the given SSRC matches this source's [primarySSRC].
     * This is mostly useful only for determining quickly whether two source
     * descriptions describe the same source; other functions (probably [hasSsrc]) should be used
     * to match received media packets.
     *
     * @param ssrc the SSRC to match.
     * @return `true` if the specified `ssrc` is the primary SSRC
     * for this source.
     */
    fun matches(ssrc: Long) = rtpEncodings.getOrNull(0)?.primarySSRC == ssrc

    /**
     * Checks whether any encoding of this source has this [ssrc]
     */
    @Synchronized
    fun hasSsrc(ssrc: Long) = rtpEncodings.any { it.hasSsrc(ssrc) }
}

/**
 * Clone an array of media source descriptions.
 */
fun Array<MediaSourceDesc>.copy() = Array(this.size) { i -> this[i].copy() }

fun Array<MediaSourceDesc>.findRtpLayerDescs(packet: VideoRtpPacket): Collection<RtpLayerDesc> {
    return this.flatMap { it.findRtpLayerDescs(packet) }
}

fun Array<MediaSourceDesc>.findRtpSourceByPrimary(ssrc: Long): MediaSourceDesc? {
    return this.find { it.matches(ssrc) }
}

fun Array<MediaSourceDesc>.findRtpSource(ssrc: Long): MediaSourceDesc? {
    return this.find { it.hasSsrc(ssrc) }
}

fun Array<MediaSourceDesc>.findRtpSource(packet: RtpPacket): MediaSourceDesc? = findRtpSource(packet.ssrc)

fun Array<MediaSourceDesc>.findRtpEncodingId(packet: VideoRtpPacket): Int? {
    for (source in this) {
        source.findRtpEncodingDesc(packet.ssrc)?.let {
            return it.eid
        }
    }
    return null
}

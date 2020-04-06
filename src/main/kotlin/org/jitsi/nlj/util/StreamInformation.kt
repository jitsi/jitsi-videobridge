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

package org.jitsi.nlj.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Collections
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.supportsPli
import org.jitsi.nlj.format.supportsRemb
import org.jitsi.nlj.format.supportsTcc
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.utils.MediaType

/**
 * A handler installed on a specific [RtpExtensionType] to be notified
 * when that type is mapped to an id.  A null id value indicates the
 * extension mapping has been removed
 */
typealias RtpExtensionHandler = (Int?) -> Unit

typealias RtpPayloadTypesChangedHandler = (Map<Byte, PayloadType>) -> Unit

/**
 * Makes information about stream metadata (RTP extensions, payload types,
 * etc.) available and allows interested parties to add handlers for when certain
 * information is available.
 */
interface ReadOnlyStreamInformationStore : NodeStatsProducer {
    val rtpExtensions: List<RtpExtension>
    fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler)

    val rtpPayloadTypes: Map<Byte, PayloadType>
    fun onRtpPayloadTypesChanged(handler: RtpPayloadTypesChangedHandler)

    fun getLocalPrimarySsrc(secondarySsrc: Long): Long?
    fun getRemoteSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long?

    /**
     * All signaled receive SSRCs
     */
    val receiveSsrcs: Set<Long>

    /**
     * A list of all primary media (audio and video) SSRCs for which the
     * endpoint associated with this stream information store sends video
     * (does not include things like RTX)
     */
    val primaryMediaSsrcs: Set<Long>

    /**
     * A list of all primary video SSRCs for which the endpoint associated
     * with this stream information store sends video (does not include
     * RTX)
     */
    val primaryVideoSsrcs: Set<Long>

    val supportsPli: Boolean
    val supportsFir: Boolean
    val supportsRemb: Boolean
    val supportsTcc: Boolean
}

/**
 * A writable stream information store
 */
interface StreamInformationStore : ReadOnlyStreamInformationStore {
    fun addRtpExtensionMapping(rtpExtension: RtpExtension)
    fun clearRtpExtensions()

    fun addRtpPayloadType(payloadType: PayloadType)
    fun clearRtpPayloadTypes()

    fun addSsrcAssociation(ssrcAssociation: SsrcAssociation)

    fun addReceiveSsrc(ssrc: Long, mediaType: MediaType)
    fun removeReceiveSsrc(ssrc: Long)
}

class StreamInformationStoreImpl : StreamInformationStore {
    private val extensionsLock = Any()
    private val extensionHandlers =
        mutableMapOf<RtpExtensionType, MutableList<RtpExtensionHandler>>()
    private val _rtpExtensions: MutableList<RtpExtension> = CopyOnWriteArrayList()
    override val rtpExtensions: List<RtpExtension>
        get() = _rtpExtensions

    private val payloadTypesLock = Any()
    private val payloadTypeHandlers = mutableListOf<RtpPayloadTypesChangedHandler>()
    private val _rtpPayloadTypes: MutableMap<Byte, PayloadType> = ConcurrentHashMap()
    override val rtpPayloadTypes: Map<Byte, PayloadType> = Collections.unmodifiableMap(_rtpPayloadTypes)

    private val localSsrcAssociations = SsrcAssociationStore("Local SSRC Associations")
    private val remoteSsrcAssociations = SsrcAssociationStore("Remote SSRC Associations")

    private val receiveSsrcStore = ReceiveSsrcStore(localSsrcAssociations)
    override val receiveSsrcs: Set<Long>
        get() = receiveSsrcStore.receiveSsrcs
    override val primaryMediaSsrcs: Set<Long>
        get() = receiveSsrcStore.primaryMediaSsrcs
    override val primaryVideoSsrcs: Set<Long>
        get() = receiveSsrcStore.primaryVideoSsrcs

    // Support for FIR, PLI, REMB and TCC is declared per-payload type, but currently our code is not payload-type
    // aware. So until this changes we will just check if any of the PTs supports the relevant feedback.
    // We always assume support for FIR.
    override var supportsFir: Boolean = true
        private set

    override var supportsPli: Boolean = false
        private set

    override var supportsRemb: Boolean = false
        private set

    override var supportsTcc: Boolean = false
        private set

    override fun addRtpExtensionMapping(rtpExtension: RtpExtension) {
        synchronized(extensionsLock) {
            _rtpExtensions.add(rtpExtension)
            extensionHandlers.get(rtpExtension.type)?.forEach { it(rtpExtension.id.toInt()) }
        }
    }

    override fun clearRtpExtensions() {
        synchronized(extensionsLock) {
            _rtpExtensions.clear()
            extensionHandlers.values.forEach { handlers -> handlers.forEach { it(null) } }
        }
    }

    override fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler) {
        synchronized(extensionsLock) {
            extensionHandlers.getOrPut(rtpExtensionType, { mutableListOf() }).add(handler)
            _rtpExtensions.find { it.type == rtpExtensionType }?.let { handler(it.id.toInt()) }
        }
    }

    override fun addRtpPayloadType(payloadType: PayloadType) {
        synchronized(payloadTypesLock) {
            _rtpPayloadTypes[payloadType.pt] = payloadType
            supportsPli = rtpPayloadTypes.values.find { it.rtcpFeedbackSet.supportsPli() } != null
            supportsRemb = rtpPayloadTypes.values.find { it.rtcpFeedbackSet.supportsRemb() } != null
            supportsTcc = rtpPayloadTypes.values.find { it.rtcpFeedbackSet.supportsTcc() } != null
            payloadTypeHandlers.forEach { it(_rtpPayloadTypes) }
        }
    }

    override fun clearRtpPayloadTypes() {
        synchronized(payloadTypesLock) {
            _rtpPayloadTypes.clear()
            supportsPli = false
            supportsRemb = false
            supportsTcc = false
            payloadTypeHandlers.forEach { it(_rtpPayloadTypes) }
        }
    }

    override fun onRtpPayloadTypesChanged(handler: RtpPayloadTypesChangedHandler) {
        synchronized(payloadTypesLock) {
            payloadTypeHandlers.add(handler)
            handler(_rtpPayloadTypes)
        }
    }

    // NOTE(brian): Currently, we only have a use case to do a mapping of
    // secondary -> primary for local SSRCs and primary -> secondary for
    // remote SSRCs
    override fun getLocalPrimarySsrc(secondarySsrc: Long): Long? =
        localSsrcAssociations.getPrimarySsrc(secondarySsrc)

    override fun getRemoteSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? =
        remoteSsrcAssociations.getSecondarySsrc(primarySsrc, associationType)

    override fun addSsrcAssociation(ssrcAssociation: SsrcAssociation) {
        when (ssrcAssociation) {
            is LocalSsrcAssociation -> localSsrcAssociations.addAssociation(ssrcAssociation)
            is RemoteSsrcAssociation -> remoteSsrcAssociations.addAssociation(ssrcAssociation)
        }
    }

    override fun addReceiveSsrc(ssrc: Long, mediaType: MediaType) =
        receiveSsrcStore.addReceiveSsrc(ssrc, mediaType)

    override fun removeReceiveSsrc(ssrc: Long) =
        receiveSsrcStore.removeReceiveSsrc(ssrc)

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Stream Information Store").apply {
        addBlock(NodeStatsBlock("RTP Extensions").apply {
            rtpExtensions.forEach { addString(it.id.toString(), it.type.toString()) }
        })
        addBlock(NodeStatsBlock("RTP Payload Types").apply {
            rtpPayloadTypes.forEach { addString(it.key.toString(), it.value.toString()) }
        })
        addBlock(localSsrcAssociations.getNodeStats())
        addBlock(remoteSsrcAssociations.getNodeStats())
        addBlock(receiveSsrcStore.getNodeStats())
        addBoolean("supports_pli", supportsPli)
        addBoolean("supports_fir", supportsFir)
    }
}

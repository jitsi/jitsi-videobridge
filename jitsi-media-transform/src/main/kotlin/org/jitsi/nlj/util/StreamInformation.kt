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

import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.supportsFir
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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
interface ReadOnlyStreamInformationStore {
    val rtpExtensions: List<RtpExtension>
    fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler)

    val rtpPayloadTypes: Map<Byte, PayloadType>
    fun onRtpPayloadTypesChanged(handler: RtpPayloadTypesChangedHandler)

    val supportsPli: Boolean
    val supportsFir: Boolean
}

/**
 * A writable stream information store
 */
interface StreamInformationStore : ReadOnlyStreamInformationStore {
    fun addRtpExtensionMapping(rtpExtension: RtpExtension)
    fun clearRtpExtensions()

    fun addRtpPayloadType(payloadType: PayloadType)
    fun clearRtpPayloadTypes()
}

class StreamInformationStoreImpl : StreamInformationStore, NodeStatsProducer {
    private val extensionsLock = Any()
    private val extensionHandlers =
        mutableMapOf<RtpExtensionType, MutableList<RtpExtensionHandler>>()
    private val _rtpExtensions: MutableList<RtpExtension> = CopyOnWriteArrayList()
    override val rtpExtensions: List<RtpExtension>
        get() = _rtpExtensions

    private val payloadTypesLock = Any()
    private val payloadTypeHandlers = mutableListOf<RtpPayloadTypesChangedHandler>()
    private val _rtpPayloadTypes: MutableMap<Byte, PayloadType> = ConcurrentHashMap()
    override val rtpPayloadTypes: Map<Byte, PayloadType>
        get() = _rtpPayloadTypes

    override var supportsPli: Boolean = false
        private set

    // Support for FIR and PLI is declared per-payload type, but currently
    // our code which requests FIR and PLI is not payload-type aware. So
    // until this changes we will just check if any of the PTs supports
    // FIR and PLI. This means that we effectively always assume support for FIR.
    override var supportsFir: Boolean = true
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
            supportsPli = rtpPayloadTypes.values.find { it.rtcpFeedbackSet.supportsFir() } != null
            payloadTypeHandlers.forEach { it(_rtpPayloadTypes) }
        }
    }

    override fun clearRtpPayloadTypes() {
        synchronized(payloadTypesLock) {
            _rtpPayloadTypes.clear()
            supportsPli = false
            payloadTypeHandlers.forEach { it(_rtpPayloadTypes) }
        }
    }

    override fun onRtpPayloadTypesChanged(handler: RtpPayloadTypesChangedHandler) {
        synchronized(payloadTypesLock) {
            payloadTypeHandlers.add(handler)
            handler(_rtpPayloadTypes)
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Stream Information Store").apply {
            addBlock(NodeStatsBlock("RTP Extensions").apply {
                rtpExtensions.forEach { addString(it.id.toString(), it.type.toString()) }
            })
            addBlock(NodeStatsBlock("RTP Payload Types").apply {
                rtpPayloadTypes.forEach { addString(it.key.toString(), it.value.toString()) }
            })
            addBoolean("supports_pli", supportsPli)
            addBoolean("supports_fir", supportsFir)
        }
    }
}
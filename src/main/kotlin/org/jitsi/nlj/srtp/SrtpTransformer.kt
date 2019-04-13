/*
 * Copyright @ 2019 - present 8x8, Inc.
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

package org.jitsi.nlj.srtp

import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging.Logger
import org.jitsi.impl.neomedia.transform.srtp.BaseSRTPCryptoContext
import org.jitsi.impl.neomedia.transform.srtp.SRTCPCryptoContext
import org.jitsi.impl.neomedia.transform.srtp.SRTPContextFactory
import org.jitsi.impl.neomedia.transform.srtp.SRTPCryptoContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Implements the methods common to all 4 transformer implementation (encrypt/decrypt for SRTP/SRTCP)
 */
abstract class AbstractSrtpTransformer<CryptoContextType : BaseSRTPCryptoContext>(
    protected val contextFactory: SRTPContextFactory
) {
    /**
     * All the known SSRC's corresponding SRTPCryptoContexts
     */
    private val contexts: MutableMap<Long, CryptoContextType> = ConcurrentHashMap()

    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    fun close() {
        synchronized(contexts) {
            contextFactory.close()
            contexts.values.forEach { it.close() }
        }
    }

    /**
     * Gets the context for a specific SSRC and index.
     */
    protected fun getContext(ssrc: Long, index: Long): CryptoContextType? {
        synchronized(contexts) {
            contexts[ssrc]?.let { return it }

            val derivedContext = deriveContext(ssrc, index) ?: run {
                logger.warn("Failed to derive context for $ssrc $index")
                return null
            }
            contexts[ssrc] = derivedContext
            return derivedContext
        }
    }

    /**
     * Derives a new context for a specific SSRC and index.
     */
    protected abstract fun deriveContext(ssrc: Long, index: Long): CryptoContextType?

    /**
     * Gets the context to use for a specific packet.
     */
    protected abstract fun getContext(packetInfo: PacketInfo): CryptoContextType?

    /**
     * Does the actual transformation of a packet, with a specific context.
     */
    protected abstract fun transform(packetInfo: PacketInfo, context: CryptoContextType): Boolean

    /**
     * Transforms a packet, returns [false] on failure.
     */
    fun transform(packetInfo: PacketInfo): Boolean {
        val context = getContext(packetInfo) ?: return false

        return transform(packetInfo, context)
    }
}

/**
 * Implements methods common for the two SRTP transformer implementations.
 */
abstract class SrtpTransformer(contextFactory: SRTPContextFactory) : AbstractSrtpTransformer<SRTPCryptoContext>(contextFactory) {
    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    override fun deriveContext(ssrc: Long, index: Long): SRTPCryptoContext? {
        val defaultContext = contextFactory.defaultContext ?: return null
        val context = defaultContext.deriveContext(ssrc.toInt(), 0, 0)
        context.deriveSrtpKeys(index)
        return context
    }

    override fun getContext(packetInfo: PacketInfo): SRTPCryptoContext? {
        val rtpPacket: RtpPacket = packetInfo.packet as? RtpPacket
            ?: run {
                logger.warn("Can not handle non-RTP packet: ${packetInfo.packet.javaClass}")
                return null
            }
        return getContext(rtpPacket.ssrc, rtpPacket.sequenceNumber.toLong())
    }
}

/**
 * Implements methods common for the two SRTP transformer implementations.
 */
abstract class SrtcpTransformer(contextFactory: SRTPContextFactory) : AbstractSrtpTransformer<SRTCPCryptoContext>(contextFactory) {
    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    override fun deriveContext(ssrc: Long, index: Long): SRTCPCryptoContext? {
        val defaultContext = contextFactory.defaultContextControl ?: return null
        val context = defaultContext.deriveContext(ssrc.toInt())
        context.deriveSrtcpKeys()
        return context
    }

    override fun getContext(packetInfo: PacketInfo): SRTCPCryptoContext? {
        // Contrary to RTP packets, RTCP packets do not get parsed before they are
        // decrypted. So (if this is a decrypting transformer) we are working with
        // an UnparsedPacket here and need to read the SSRC manually.
        val senderSsrc = RtcpHeader.getSenderSsrc(packetInfo.packet.getBuffer(), packetInfo.packet.getOffset())
        return getContext(senderSsrc, 0)
    }
}

/**
 * A transformer which decrypts SRTCP packets.
 */
class SrtcpDecryptTransformer(contextFactory: SRTPContextFactory) : SrtcpTransformer(contextFactory) {
    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    override fun transform(packetInfo: PacketInfo, context: SRTCPCryptoContext): Boolean {
        context.reverseTransformPacket(packetInfo.packet)
        packetInfo.resetPayloadVerification()
        return true
    }
}

/**
 * A transformer which encrypts RTCP packets (producing SRTCP packets). Note that as opposed to the other transformers,
 * this one replaces the [Packet].
 */
class SrtcpEncryptTransformer(contextFactory: SRTPContextFactory) : SrtcpTransformer(contextFactory) {
    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    override fun transform(packetInfo: PacketInfo, context: SRTCPCryptoContext): Boolean {
        context.transformPacket(packetInfo.packet)
        // We convert the encrypted RTCP packet to an UnparsedPacket because
        // we don't want any of the RTCP fields trying to parse the data
        // (since it's now encrypted)
        // TODO: better way we can do this?  it's not typically a problem
        // in the pipeline's usage, but it's a bit of a landmine since by
        // accessing the packet it can try and parse the fields.
        packetInfo.packet = packetInfo.packet.toOtherType(::UnparsedPacket)
        packetInfo.resetPayloadVerification()
        return true
    }
}

/**
 * A transformer which decrypts SRTP packets. Note that it expects the [Packet] to have already been parsed as
 * [RtpPacket].
 */
class SrtpDecryptTransformer(contextFactory: SRTPContextFactory) : SrtpTransformer(contextFactory) {
    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    override fun transform(packetInfo: PacketInfo, context: SRTPCryptoContext): Boolean {
        // For silence packets we update the ROC (if authentication passes), but don't decrypt
        return context.reverseTransformPacket(packetInfo.packetAs(), packetInfo.shouldDiscard).apply {
            packetInfo.resetPayloadVerification()
        }
    }
}

/**
 * A transformer which encrypts RTP packets (producing SRTP packets).
 */
class SrtpEncryptTransformer(contextFactory: SRTPContextFactory) : SrtpTransformer(contextFactory) {
    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    override fun transform(packetInfo: PacketInfo, context: SRTPCryptoContext): Boolean {
        return context.transformPacket(packetInfo.packetAs()).apply {
            packetInfo.packet = packetInfo.packet.toOtherType(::UnparsedPacket)
            packetInfo.resetPayloadVerification()
        }
    }
}

/**
 * Holds the four SRTP-related transformers for a session.
 */
data class SrtpTransformers(
    val srtpDecryptTransformer: SrtpDecryptTransformer,
    val srtpEncryptTransformer: SrtpEncryptTransformer,
    val srtcpDecryptTransformer: SrtcpDecryptTransformer,
    val srtcpEncryptTransformer: SrtcpEncryptTransformer
)

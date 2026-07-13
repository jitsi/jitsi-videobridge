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
import org.jitsi.nlj.srtp.SrtpConfig.Companion.maxConsecutivePacketsDiscardedEarly
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.srtp.BaseSrtpCryptoContext
import org.jitsi.srtp.SrtcpCryptoContext
import org.jitsi.srtp.SrtpContextFactory
import org.jitsi.srtp.SrtpCryptoContext
import org.jitsi.srtp.SrtpErrorStatus
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.logging2.cwarn

/**
 * Implements the methods common to all 4 transformer implementation (encrypt/decrypt for SRTP/SRTCP)
 */
abstract class AbstractSrtpTransformer<CryptoContextType : BaseSrtpCryptoContext>(
    protected val contextFactory: SrtpContextFactory,
    parentLogger: Logger
) {
    protected val logger = createChildLogger(parentLogger)

    /**
     * All the known SSRC's corresponding SrtpCryptoContexts. Bounded with an LRU to limit the number of SSRCs tracked;
     * evicted contexts are simply dropped (their native cipher resources are freed by a [java.lang.ref.Cleaner], and
     * the contexts hold no other state that needs explicit cleanup). All access is guarded by synchronizing on the map.
     * Note that eviction resets the ROC and replay state for an SSRC, so the cap must be high enough that
     * legitimately-active SSRCs are never evicted.
     */
    private val contexts: MutableMap<Long, CryptoContextType> = LRUCache(MAX_SSRCS, true)

    /**
     * The number of SSRCs for which a crypto context is currently cached.
     */
    val numCachedContexts: Int
        get() = synchronized(contexts) { contexts.size }

    fun close() {
        synchronized(contexts) {
            contextFactory.close()
        }
    }

    /**
     * Whether a context that is derived on demand (because none was cached for the SSRC yet) should only be added to
     * [contexts] after the first transform operation using it succeeds. This is used for decryption, so that we do not
     * retain a context for an SSRC whose packets never successfully authenticate.
     */
    protected open val cacheContextOnlyOnSuccessfulTransform: Boolean = false

    /**
     * Derives a new context for a specific SSRC and index.
     */
    protected abstract fun deriveContext(ssrc: Long, index: Long): CryptoContextType?

    /**
     * Gets the SSRC and index (sequence number) to use for a specific packet, or null if it can not be handled.
     */
    protected abstract fun getSsrcAndIndex(packetInfo: PacketInfo): SsrcAndIndex?

    /**
     * Does the actual transformation of a packet, with a specific context. [isNewContext] indicates that the context was
     * just derived and is not (yet) cached, which a transformer may use to force a full transform (e.g. to bypass the
     * "skip decryption" optimization) so that the returned status reliably reflects whether the context is valid.
     */
    protected abstract fun transform(
        packetInfo: PacketInfo,
        context: CryptoContextType,
        isNewContext: Boolean
    ): SrtpErrorStatus

    /**
     * Transforms a packet, returns [SrtpErrorStatus.OK] on success or another [SrtpErrorStatus] on failure.
     */
    fun transform(packetInfo: PacketInfo): SrtpErrorStatus {
        val ssrcAndIndex = getSsrcAndIndex(packetInfo) ?: return SrtpErrorStatus.FAIL
        val ssrc = ssrcAndIndex.ssrc

        // Look up (or derive) the context under the lock. We must synchronize even the lookup because the map is not
        // necessarily thread-safe (and an access-order map is structurally modified on read). The lock is only held
        // for the lookup/derivation, not for the transform itself.
        var isNewContext = false
        val context = synchronized(contexts) {
            contexts[ssrc] ?: run {
                val derivedContext = deriveContext(ssrc, ssrcAndIndex.index) ?: run {
                    logger.warn("Failed to derive context for $ssrc ${ssrcAndIndex.index}")
                    return SrtpErrorStatus.FAIL
                }
                isNewContext = true
                if (!cacheContextOnlyOnSuccessfulTransform) {
                    contexts[ssrc] = derivedContext
                }
                derivedContext
            }
        }

        val status = transform(packetInfo, context, isNewContext)
        if (isNewContext && cacheContextOnlyOnSuccessfulTransform && status == SrtpErrorStatus.OK) {
            synchronized(contexts) {
                contexts.putIfAbsent(ssrc, context)
            }
        }
        return status
    }

    /**
     * The SSRC and index (sequence number) identifying the context to use for a packet.
     */
    protected data class SsrcAndIndex(val ssrc: Long, val index: Long)

    companion object {
        /** The maximum number of SSRCs to keep crypto contexts for. */
        const val MAX_SSRCS = 1024
    }
}

/**
 * Implements methods common for the two SRTP transformer implementations.
 */
abstract class SrtpTransformer(
    contextFactory: SrtpContextFactory,
    logger: Logger
) : AbstractSrtpTransformer<SrtpCryptoContext>(contextFactory, logger) {

    override fun deriveContext(ssrc: Long, index: Long): SrtpCryptoContext? =
        contextFactory.deriveContext(ssrc.toInt(), 0) ?: null

    override fun getSsrcAndIndex(packetInfo: PacketInfo): SsrcAndIndex? {
        val rtpPacket: RtpPacket = packetInfo.packet as? RtpPacket ?: run {
            logger.cwarn { "Can not handle non-RTP packet: ${packetInfo.packet.javaClass}" }
            return null
        }
        return SsrcAndIndex(rtpPacket.ssrc, rtpPacket.sequenceNumber.toLong())
    }
}

/**
 * Implements methods common for the two SRTCP transformer implementations.
 */
abstract class SrtcpTransformer(
    contextFactory: SrtpContextFactory,
    logger: Logger
) : AbstractSrtpTransformer<SrtcpCryptoContext>(contextFactory, logger) {

    override fun deriveContext(ssrc: Long, index: Long): SrtcpCryptoContext? =
        contextFactory.deriveControlContext(ssrc.toInt()) ?: null

    override fun getSsrcAndIndex(packetInfo: PacketInfo): SsrcAndIndex? {
        // Contrary to RTP packets, RTCP packets do not get parsed before they are
        // decrypted. So (if this is a decrypting transformer) we are working with
        // an UnparsedPacket here and need to read the SSRC manually.
        val senderSsrc = RtcpHeader.getSenderSsrc(packetInfo.packet.getBuffer(), packetInfo.packet.getOffset())
        return SsrcAndIndex(senderSsrc, 0)
    }
}

/**
 * A transformer which decrypts SRTCP packets.
 */
class SrtcpDecryptTransformer(
    contextFactory: SrtpContextFactory,
    parentLogger: Logger
) : SrtcpTransformer(contextFactory, parentLogger) {

    // Only cache a derived context once it has successfully authenticated a packet.
    override val cacheContextOnlyOnSuccessfulTransform = true

    override fun transform(
        packetInfo: PacketInfo,
        context: SrtcpCryptoContext,
        isNewContext: Boolean
    ): SrtpErrorStatus {
        return context.reverseTransformPacket(packetInfo.packet).apply {
            packetInfo.resetPayloadVerification()
        }
    }
}

/**
 * A transformer which encrypts RTCP packets (producing SRTCP packets). Note that as opposed to the other transformers,
 * this one replaces the [Packet].
 */
class SrtcpEncryptTransformer(
    contextFactory: SrtpContextFactory,
    parentLogger: Logger
) : SrtcpTransformer(contextFactory, parentLogger) {

    override fun transform(
        packetInfo: PacketInfo,
        context: SrtcpCryptoContext,
        isNewContext: Boolean
    ): SrtpErrorStatus {
        return context.transformPacket(packetInfo.packet).apply {
            // We convert the encrypted RTCP packet to an UnparsedPacket because
            // we don't want any of the RTCP fields trying to parse the data
            // (since it's now encrypted)
            // TODO: better way we can do this?  it's not typically a problem
            // in the pipeline's usage, but it's a bit of a landmine since by
            // accessing the packet it can try and parse the fields.
            packetInfo.packet = packetInfo.packet.toOtherType(::UnparsedPacket)
            packetInfo.resetPayloadVerification()
        }
    }
}

/**
 * A transformer which decrypts SRTP packets. Note that it expects the [Packet] to have already been parsed as
 * [RtpPacket].
 */
class SrtpDecryptTransformer(
    contextFactory: SrtpContextFactory,
    parentLogger: Logger
) : SrtpTransformer(contextFactory, parentLogger) {
    var earlyDiscardedPacketsSinceLastSuccess = 0
    private val alwaysProcess = maxConsecutivePacketsDiscardedEarly <= 0

    // Only cache a derived context once it has successfully authenticated a packet.
    override val cacheContextOnlyOnSuccessfulTransform = true

    override fun transform(packetInfo: PacketInfo, context: SrtpCryptoContext, isNewContext: Boolean): SrtpErrorStatus {
        // We want to avoid authenticating and decrypting packets that we are going to discarded (e.g. silence). We
        // can not just discard them without passing them to the SRTP stack, because this will eventually break the ROC.
        // Here we bypass the SRTP stack for packets marked to be discarded, but make sure that we haven't dropped too
        // many consecutive packets. We never bypass for a newly-derived context: it must actually decrypt (and thus
        // authenticate) a packet before it is cached, so the returned status must reflect whether the context is valid.
        if (packetInfo.shouldDiscard) {
            return if (isNewContext ||
                alwaysProcess ||
                earlyDiscardedPacketsSinceLastSuccess++ > maxConsecutivePacketsDiscardedEarly
            ) {
                doTransform(packetInfo, context)
            } else {
                // Bypass the SRTP stack. The packet is already marked to be discarded, so there's no error condition.
                SrtpErrorStatus.OK
            }
        }

        return doTransform(packetInfo, context)
    }

    private fun doTransform(packetInfo: PacketInfo, context: SrtpCryptoContext): SrtpErrorStatus =
        context.reverseTransformPacket(packetInfo.packetAs(), packetInfo.shouldDiscard).also {
            packetInfo.resetPayloadVerification()
            if (it == SrtpErrorStatus.OK) {
                earlyDiscardedPacketsSinceLastSuccess = 0
            }
        }
}

/**
 * A transformer which encrypts RTP packets (producing SRTP packets).
 */
class SrtpEncryptTransformer(
    contextFactory: SrtpContextFactory,
    parentLogger: Logger
) : SrtpTransformer(contextFactory, parentLogger) {

    override fun transform(packetInfo: PacketInfo, context: SrtpCryptoContext, isNewContext: Boolean): SrtpErrorStatus {
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
) {
    fun close() {
        srtpDecryptTransformer.close()
        srtpEncryptTransformer.close()
        srtcpDecryptTransformer.close()
        srtcpEncryptTransformer.close()
    }
}

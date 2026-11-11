/*
 * Copyright @ 2026 - Present, 8x8, Inc.
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

import org.jitsi.mediajson.MediaEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.SyntheticSourceSendingChangeEvent
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.SyntheticSourceKind
import org.jitsi.videobridge.util.ByteBufferPool
import java.time.Clock
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Injects the audio of the synthetic sources of a conference (translated audio, a voice agent's audio), which their
 * producers send as mediajson events over an export websocket. The RTP packets of a source and its sending changes
 * get their numbers from one [SyntheticSourceRtpContinuity], so they stay on one timeline.
 */
class SyntheticSourceInjector(
    private val conference: ConferenceAccess,
    clock: Clock,
    parentLogger: Logger
) {
    /**
     * The conference-side operations the injector depends on. Implemented by the conference and passed in, so the
     * injector doesn't depend on the conference directly and can be driven by a fake in tests.
     */
    interface ConferenceAccess {
        /** The synthetic audio source with the given name, or null if there is none. */
        fun findSyntheticAudioSource(sourceName: String): AudioSourceDesc?

        /** The Opus payload type negotiated in the conference, or null if there is none. */
        val opusPayloadType: PayloadType?

        /** The ID negotiated in the conference for the ssrc-audio-level header extension (RFC 6464), or null. */
        val audioLevelExtensionId: Int?

        /** Takes a packet built for a synthetic source, to route it in the conference. */
        fun handleIncomingPacket(packetInfo: PacketInfo)

        /** Broadcasts a message to the clients of the conference, and to its relays if [sendToRelays] is set. */
        fun broadcastMessage(message: BridgeChannelMessage, sendToRelays: Boolean)

        /** Feeds the audio level (RFC 6464) of an injected packet of a voice agent to speech activity. */
        fun feedAgentSpeechActivity(source: AudioSourceDesc, audioLevel: Int)
    }

    private val logger = createChildLogger(parentLogger)

    /**
     * Keeps the RTP numbering of each injected synthetic source continuous when its producer starts again. Its state
     * for a source stays when the source is removed, because a source that is re-added with the same SSRC must
     * continue from the numbers already sent.
     */
    private val rtpContinuity = SyntheticSourceRtpContinuity(clock, parentLogger)

    /**
     * Source names for which an injected (translated) synthetic source has already been created, so we log its
     * creation only once. Cleared when the source is removed, so a re-created source logs again.
     */
    private val loggedInjectedSources: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Tags of received translated-media events that didn't match any synthetic source, so we warn about an
     * unrecognized tag only once rather than on every packet. Bounded (an unknown tag is never removed via
     * [onSourcesRemoved], since it corresponds to no known source): the worst case of eviction is warning again for a
     * tag we saw long ago.
     */
    private val loggedUnknownMediaTags: MutableSet<String> =
        Collections.synchronizedSet(LRUCache.lruSet(LOG_CACHE_SIZE, true))

    /**
     * Names of synthetic sources that have been removed (unsubscribed) and not since re-added. Media for these can
     * still arrive briefly -- the translator may have sent it before it saw the unsubscribe -- so it's an expected
     * transient rather than an unrecognized tag, and is dropped without a warning. Bounded (an entry is removed only
     * when a source with the same name is re-added, so uniquely-named sources would otherwise accumulate for the
     * conference's lifetime): the worst case of eviction is warning once for late media of a long-removed source.
     */
    private val removedSyntheticSources: MutableSet<String?> =
        Collections.synchronizedSet(LRUCache.lruSet(LOG_CACHE_SIZE, true))

    /**
     * Whether we have warned about translated media arriving before any Opus payload type is negotiated, so the
     * warning is logged once rather than per media event.
     */
    private val loggedNoOpusPayloadType = AtomicBoolean(false)

    /**
     * Whether we have warned about injected media carrying an audio level while no ssrc-audio-level extension is
     * negotiated, so the warning is logged once rather than per media event.
     */
    private val loggedNoAudioLevelExtension = AtomicBoolean(false)

    /** Notes that audio sources were added to the conference. */
    fun onSourcesAdded(audioSources: Set<AudioSourceDesc>) {
        audioSources.forEach { removedSyntheticSources.remove(it.sourceName) }
    }

    /** Notes that audio sources were removed from the conference. */
    fun onSourcesRemoved(audioSources: Set<AudioSourceDesc>) {
        audioSources.forEach {
            loggedInjectedSources.remove(it.sourceName)
            loggedUnknownMediaTags.remove(it.sourceName)
            if (it.synthetic) {
                removedSyntheticSources.add(it.sourceName)
            }
        }
    }

    /**
     * Handles a translated-audio "media" event received from a translator over the export websocket. The event's tag
     * names the synthetic source the translated audio belongs to; we build an Opus RTP packet on that source's SSRC
     * and inject it into the conference, where the synthetic-source routing delivers it (only to endpoints that
     * explicitly subscribed to that source, and on to relays).
     */
    fun handleMediaEvent(mediaEvent: MediaEvent) {
        val media = mediaEvent.media
        val sourceName = media.tag

        val source = conference.findSyntheticAudioSource(sourceName)
        if (source == null) {
            if (removedSyntheticSources.contains(sourceName)) {
                // Expected transient: the translator sent this before it saw our unsubscribe of the source.
                logger.debug { "Dropping translated media for removed synthetic source: $sourceName" }
            } else if (loggedUnknownMediaTags.add(sourceName)) {
                logger.warn("Received translated media for unknown synthetic source: $sourceName")
            }
            return
        }

        val opusPayloadType = conference.opusPayloadType
        if (opusPayloadType == null) {
            if (loggedNoOpusPayloadType.compareAndSet(false, true)) {
                logger.warn("Received translated media but no Opus payload type is negotiated; dropping.")
            }
            return
        }

        if (loggedInjectedSources.add(sourceName)) {
            logger.info("Creating injected synthetic source $sourceName (ssrc ${source.ssrc})")
        }

        try {
            val payload = Base64.getDecoder().decode(media.payload)
            val headerSize = RtpHeader.FIXED_HEADER_SIZE_BYTES
            val offset = RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET
            // Use a pooled buffer with the standard head and tail room, like other packet-construction sites, so
            // downstream stages (header extensions, the SRTP auth tag) don't need to reallocate and the buffer
            // round-trips through the pool.
            val buffer = ByteBufferPool.getBuffer(
                offset + headerSize + payload.size + Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
            )
            // The pooled buffer isn't zeroed, and the setters below don't cover every header field (e.g. the
            // padding/extension/CSRC-count and marker bits), so clear the header region first.
            buffer.fill(0, offset, offset + headerSize)
            payload.copyInto(buffer, offset + headerSize)

            val rtpPacket = AudioRtpPacket(buffer, offset, headerSize + payload.size)
            rtpPacket.version = 2
            rtpPacket.payloadType = opusPayloadType.pt.toInt()
            // The producer numbers its media from the start again on every connection, while the SSRC stays: the
            // numbers are kept continuous here. The timestamp of the producer is not wrapped, but the RTP timestamp
            // is a 32-bit wrapping field. The timestamp setter writes only the low 32 bits but caches the value it is
            // given, so the timestamp comes back wrapped: the packet's cached timestamp is then consistent with its
            // bytes (and with the sending-change notification).
            val numbers = rtpContinuity.nextPacket(source.ssrc, media.chunk, media.timestamp)
            rtpPacket.sequenceNumber = numbers.sequenceNumber
            rtpPacket.timestamp = numbers.timestamp
            rtpPacket.ssrc = source.ssrc

            // Optional RFC 6464 audio level, so clients can show a level indicator for the synthetic source. The
            // producer computes it from the PCM it encoded (the bridge never decodes the Opus). Absent in media from
            // producers that predate the field, in which case the packet has no extension, as before.
            val audioLevel = media.audioLevel
            if (audioLevel != null) {
                val audioLevelExtId = conference.audioLevelExtensionId
                if (audioLevelExtId != null) {
                    AudioLevelHeaderExtension.addToPacket(rtpPacket, audioLevelExtId, audioLevel, media.vad == true)
                } else if (loggedNoAudioLevelExtension.compareAndSet(false, true)) {
                    logger.warn(
                        "Injected media carries an audio level but no ssrc-audio-level extension is negotiated; " +
                            "not adding it."
                    )
                }

                // This packet enters via handleIncomingPacket, not an endpoint's receive pipeline, so feed speech
                // activity here for a voice agent, with the same loudness the receive pipeline derives (127 is
                // silence on the wire), so a talking bot can be the dominant speaker. Translated audio never is.
                if (source.kind == SyntheticSourceKind.AGENT) {
                    conference.feedAgentSpeechActivity(source, audioLevel)
                }
            }

            val packetInfo = PacketInfo(rtpPacket)
            packetInfo.payloadType = opusPayloadType
            // Deliberately leave the endpoint ID unset: this is bridge-generated audio, not an endpoint's own live
            // audio, so there is no self-echo to suppress. Delivery (including to the source's owner) is governed
            // entirely by the audio subscriptions, so an endpoint can subscribe to its own synthetic source.

            conference.handleIncomingPacket(packetInfo)
        } catch (e: Exception) {
            logger.warn("Failed to handle translated media for source $sourceName", e)
        }
    }

    /**
     * Notes that the producer of the given synthetic sources numbers their media from the start again, because the
     * connection to it opened.
     *
     * @param sourceNames the requests of the connect; a name that is not a synthetic source is ignored
     */
    fun handleProducerConnected(sourceNames: List<String>) {
        try {
            rtpContinuity.producerStarted(sourceNames.mapNotNull { conference.findSyntheticAudioSource(it)?.ssrc })
        } catch (e: Exception) {
            // The exporter calls this while its websocket opens: a failure here must not fail the connection.
            logger.warn("Failed to handle the new connection of the producer of $sourceNames", e)
        }
    }

    /**
     * Handles a synthetic source's sending-state change, derived from the `start`/`stop` mediajson events a
     * translator sends to bracket a "talk" of translated audio. Resolves the named synthetic source (dropping the
     * change if it isn't a known synthetic source, like [handleMediaEvent]) and broadcasts a
     * [SyntheticSourceSendingChangeEvent], carrying the source's kind, to the conference's clients (and relays).
     *
     * @param sourceName the synthetic source whose sending state changed
     * @param sending true if the source started sending, false if it stopped
     * @param timestamp the timestamp (media clock) at which the change takes effect, on the same timeline as the
     * producer's media; it is mapped to the timeline of the injected media and wrapped to 32 bits (RTP timestamps
     * are 32-bit and wrap)
     */
    fun handleSendingChange(sourceName: String, sending: Boolean, timestamp: Long) {
        val source = conference.findSyntheticAudioSource(sourceName)
        if (source == null) {
            if (removedSyntheticSources.contains(sourceName)) {
                // Expected transient: the translator sent this before it saw our unsubscribe of the source.
                logger.debug { "Dropping sending-change for removed synthetic source: $sourceName" }
            } else if (loggedUnknownMediaTags.add(sourceName)) {
                logger.warn("Received sending-change for unknown synthetic source: $sourceName")
            }
            return
        }

        try {
            // The producer's timestamp is not wrapped and starts again when the producer reconnects. Map it to the
            // timeline of this source's injected media (see handleMediaEvent), wrapped to 32 bits: the client
            // (lib-jitsi-meet) drops a change whose timestamp is outside 0..0xFFFFFFFF.
            val rtpTimestamp = rtpContinuity.sendingChangeTimestamp(source.ssrc, timestamp)
            conference.broadcastMessage(
                SyntheticSourceSendingChangeEvent(sourceName, sending, rtpTimestamp, source.kind),
                sendToRelays = true
            )
        } catch (e: Exception) {
            logger.warn("Failed to broadcast synthetic source sending change for $sourceName", e)
        }
    }

    companion object {
        /**
         * The maximum size of the bounded sets that suppress repeated log messages ([loggedUnknownMediaTags],
         * [removedSyntheticSources]).
         */
        private const val LOG_CACHE_SIZE = 1000
    }
}

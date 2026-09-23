/*
 * Copyright @ 2026 - present 8x8, Inc.
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

package org.jitsi.nlj.rtcp

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.SourceEncoding
import org.jitsi.nlj.indexEncodingsBySsrc
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** How a sender of a simulcast source responds to a keyframe request for one of its encodings. */
enum class SenderKeyframeMode {
    /** Not yet determined; treated as [CLUSTERED]. */
    UNKNOWN,

    /** A request for a keyframe on any encoding produces a keyframe on every encoding, within a few frame intervals. */
    CLUSTERED,

    /** A request produces a keyframe only on the encoding it names. */
    PER_ENCODING
}

/** How the sender's keyframe mode is determined: detected from its responses, or fixed by configuration. */
object KeyframeModeConfig {
    /**
     * The configured mode, or [SenderKeyframeMode.UNKNOWN] for "auto", meaning it is detected. Parsed on each read,
     * so a detector reads it once, when it is created. The bridge also reads it at startup, so that an unrecognized
     * value is rejected then rather than when the first endpoint arrives.
     */
    val senderMode: SenderKeyframeMode by config {
        "jmt.keyframe.sender-mode".from(JitsiConfig.newConfig).convertFrom<String> { parseSenderMode(it) }
    }

    /** The configured mode, or null to detect it. */
    val fixedMode: SenderKeyframeMode?
        get() = senderMode.takeUnless { it == SenderKeyframeMode.UNKNOWN }

    private fun parseSenderMode(value: String): SenderKeyframeMode = when (value.lowercase()) {
        "auto" -> SenderKeyframeMode.UNKNOWN

        "clustered" -> SenderKeyframeMode.CLUSTERED

        "per-encoding" -> SenderKeyframeMode.PER_ENCODING

        else -> throw IllegalArgumentException(
            "Invalid jmt.keyframe.sender-mode '$value': expected auto, clustered or per-encoding"
        )
    }
}

/**
 * Detects, for each video source received from an endpoint, which [SenderKeyframeMode] its sender is in, by
 * watching how the sender answers the bridge's own keyframe requests.
 *
 * libwebrtc's libvpx encoders currently (as of Chrome 154) by default generate keyframes on every simulcast encoding
 * whenever any is asked for. However, with the WebRTC-Video-PerSsrcKeyframes field trial, or with other encoders, a
 * sender may generate a keyframe only on the encoding specified in the request. Both behaviors conform to the
 * specifications. A FIR (RFC 5104) or PLI (RFC 4585) targets a single RTP stream by its SSRC, but neither RFC says
 * whether the sender should also refresh the other simulcast streams of the same media source. RFC 8082 requires a
 * refresh of every layer only for layered bitstreams with inter-layer prediction, and leaves independently decodable
 * streams open. Nothing in signaling says which behavior a sender has, except libwebrtc's nonstandard
 * x-google-per-layer-pli format parameter, which announces the second.
 *
 * This difference matters for how keyframe requests for different encodings of one source are rate limited and
 * budgeted. In the first mode every request costs a full set of keyframes, so the requests should share one limit per
 * source. In the second mode each request costs one keyframe, so each encoding can have its own limit. The projection
 * logic works with either mode, so a wrong guess costs keyframes, not video.
 *
 * Each keyframe request the bridge sends opens an observation, which records the encoding requested, when, and which
 * encodings were being sent at the time. Keyframes then arriving on the source's encodings are recorded against
 * the observation until it closes. It closes [RESPONSE_WINDOW_MS] after the request, or [CLUSTER_WINDOW_MS] after
 * the requested encoding's keyframe if that is later. That way the other encodings' keyframes have the full cluster
 * window to follow the requested encoding's keyframe, even if it arrives late.
 *
 * When an observation closes, it is evaluated:
 * - If the requested encoding produced a keyframe, and every other encoding being sent produced a keyframe within
 *   [CLUSTER_WINDOW_MS] of it, that is evidence for [CLUSTERED].
 * - If none of the other encodings produced a keyframe at all, that is evidence for [PER_ENCODING].
 * - A keyframe on another encoding arriving later than that is neither. The sender generated that keyframe on an
 *   encoding nobody asked for, for reasons of its own, so the observation is discarded.
 * - An encoding which produced no keyframe and also sent no media between the requested encoding's keyframe and the
 *   observation's close is excluded. The sender may have turned the encoding off just then, which would make a
 *   clustered sender look per-encoding. This is judged by the encoding's last packet time, which the receive pipeline
 *   keeps.
 * - If a keyframe arrived on an encoding which was not being sent when the request was made, the observation is
 *   discarded. A sender turning an encoding on generates keyframes for that encoding in either mode.
 * - If a keyframe arrived on an unrequested encoding whose current run of frames began after the request, the
 *   observation is discarded too. The encoding had been paused, or had not started yet, and a sender generates a
 *   keyframe when an encoding resumes in either mode.
 *
 * A further request for the source while an observation is open joins it, whether for the same encoding or another
 * encoding. It extends the observation by another response window, up to [MAX_OBSERVATION_MS] in total. All the
 * encodings requested are then expected to produce keyframes, and only the encodings not requested can count as
 * evidence of the mode. Thus, a set of requests covering every live encoding leaves nothing to observe and is
 * inconclusive. The bridge sends such a set ahead of a dominant speaker change to a per-encoding sender. An encoding
 * may have been requested within [LATE_ANSWER_WINDOW_MS] before its keyframe arrived, by a request outside the
 * observation. The keyframe is a late answer to that request, and is not evidence either.
 *
 * The mode changes once [EVIDENCE_THRESHOLD] consecutive observations agree on a different mode. Changing to
 * [PER_ENCODING] takes [PER_ENCODING_EVIDENCE_THRESHOLD] observations, because the evidence for it is weaker and a
 * mistake in that direction costs more. The evidence is a keyframe which was not seen, but a keyframe whose first
 * packet is lost for longer than the cluster window is not seen either. Mistaking a clustered sender for a
 * per-encoding sender makes the bridge ask it for several sets of keyframes where one set would suffice. Mistaking
 * a per-encoding sender for a clustered sender only makes the bridge wait longer between requests.
 *
 * Times are in milliseconds of the caller's clock. The receive pipeline's clock and the requester's must agree,
 * which they do since both are wall clocks. The observation logic runs at most once per keyframe or request.
 */
class KeyframeModeDetector(parentLogger: Logger) {
    private val logger = createChildLogger(parentLogger)

    /** The mode fixed by configuration, or null to detect it; see [KeyframeModeConfig.fixedMode]. */
    private val fixedMode: SenderKeyframeMode? = KeyframeModeConfig.fixedMode

    /** The source and encoding each SSRC belongs to, found with one lookup per keyframe and request. */
    @Volatile
    private var sourcesBySsrc: Map<Long, SourceEncoding> = emptyMap()

    /** The state of each source, keyed by its primary SSRC. */
    private val sources = ConcurrentHashMap<Long, SourceState>()

    /**
     * The primary SSRCs of the sources with an open observation. The per-packet path skips its work when there are
     * none. It also closes an observation past its deadline when its source has stopped sending packets.
     */
    private val openSources: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * The earliest deadline among the open observations, or [Long.MAX_VALUE], so that the per-packet path is one
     * comparison until an observation is due. Recomputed under [deadlineLock] whenever a deadline changes.
     */
    @Volatile
    private var nextDeadlineMs = Long.MAX_VALUE
    private val deadlineLock = Any()

    private fun updateNextDeadline() = synchronized(deadlineLock) {
        var next = Long.MAX_VALUE
        openSources.forEach { next = minOf(next, sources[it]?.pendingDeadlineMs ?: Long.MAX_VALUE) }
        nextDeadlineMs = next
    }

    /** Guards the set of sources against a state being created for a source as it is removed. */
    private val sourcesLock = Any()

    fun setMediaSources(newSources: Array<MediaSourceDesc>) = synchronized(sourcesLock) {
        sourcesBySsrc = newSources.indexEncodingsBySsrc()
        val kept = newSources.map { it.primarySSRC }.toSet()
        sources.entries.removeIf { (primarySsrc, state) ->
            if (primarySsrc in kept) {
                false
            } else {
                /* A source removed while an observation of it is open: the observation will never close by itself. */
                synchronized(state) {
                    state.pending = null
                    openSources.remove(primarySsrc)
                    state.setDeadline(Long.MAX_VALUE)
                }
                true
            }
        }
    }

    private fun findSource(ssrc: Long): MediaSourceDesc? = sourcesBySsrc[ssrc]?.source

    /** The state of [source], created if needed, or null if the source has been removed in the meantime. */
    private fun stateOf(source: MediaSourceDesc): SourceState? = synchronized(sourcesLock) {
        if (sourcesBySsrc[source.primarySSRC]?.source !== source) {
            null
        } else {
            sources.computeIfAbsent(source.primarySSRC) { SourceState(it) }
        }
    }

    /**
     * Records that a keyframe request was sent for [requestedSsrc] at [nowMs]. [requestedSsrc] may be the primary
     * or the RTX SSRC of any encoding of any of the sources set by [setMediaSources]; the request is recorded against
     * that encoding. A request which is not [observable] opens or joins no observation. Such a request is part of a
     * set sent to every encoding of the source at once, and such a set leaves no encoding to observe. It is still
     * remembered, so that its answer is not taken for evidence by an observation which is open.
     */
    fun onKeyframeRequested(requestedSsrc: Long, nowMs: Long, observable: Boolean = true) {
        if (fixedMode != null) {
            return
        }
        val requested = sourcesBySsrc[requestedSsrc] ?: return
        /* Keyframes are observed by the encoding's primary SSRC, so requests are recorded by it too. */
        val ssrc = requested.encoding.primarySSRC
        val source = requested.source
        val state = stateOf(source) ?: return
        synchronized(state) {
            /* The source may have been removed since the state was found; its state would never be closed. */
            if (sources[source.primarySSRC] !== state) {
                return
            }
            /* The media path normally closes an observation whose deadline has passed, but closing an observation
             * here is safe. If any packet arrived after the deadline, the media path closed the observation first,
             * so the last packet times read here are still those from the deadline. */
            state.expire(nowMs)
            state.lastRequestedMs[ssrc] = nowMs
            /* A request for an encoding which is not being sent: the fallback to the primary SSRC when nothing the
             * receiver could use is live. Only an encoding being turned on could answer it, and that is discarded,
             * so it would waste the observation. */
            if (!observable || !requested.encoding.liveness.isLive(nowMs)) {
                return
            }
            val pending = state.pending
            if (pending != null) {
                /* A further request while the first is being answered joins the observation, and its answer is
                 * awaited too. */
                pending.requestedSsrcs.add(ssrc)
                /* Live now, whether or not it was when the observation opened; its answer is expected. */
                pending.liveSsrcs.add(ssrc)
                /* Never earlier than it is, since an answer may have extended it, see [onKeyframeObserved]. */
                pending.deadlineMs = maxOf(
                    pending.deadlineMs,
                    minOf(nowMs + RESPONSE_WINDOW_MS, pending.requestTimeMs + MAX_OBSERVATION_MS)
                )
                state.setDeadline(pending.deadlineMs)
                return
            }
            val opened = PendingRequest(ssrc, nowMs, source.liveEncodingSsrcs(nowMs).toMutableSet())
            state.pending = opened
            openSources.add(source.primarySSRC)
            state.setDeadline(opened.deadlineMs)
        }
    }

    /**
     * Records that a media packet of one of the sources arrived at [nowMs]. Called before the packet is recorded on
     * its encoding. Its only work is to close an observation whose deadline has passed. That way an observation is
     * evaluated at the first packet after its deadline, while the encodings' last packet times are still those of
     * the observation. Evaluation reads them, and a packet after the deadline must not count. Until an observation
     * is due this costs one volatile read.
     */
    fun onPacketObserved(nowMs: Long) {
        if (nowMs <= nextDeadlineMs) {
            return
        }
        /* Close any observation past its deadline, whichever source it is for. That source may have stopped sending,
         * and nothing else would close it. The loop visits only the sources with an open observation, so it is
         * short. */
        openSources.forEach { primarySsrc ->
            sources[primarySsrc]?.let { state ->
                if (nowMs > state.pendingDeadlineMs) {
                    synchronized(state) { state.expire(nowMs) }
                }
            }
        }
    }

    /** Records that a keyframe started arriving on [ssrc], an SSRC of one of the sources, at [nowMs]. */
    fun onKeyframeObserved(ssrc: Long, nowMs: Long) {
        if (fixedMode != null) {
            return
        }
        val source = findSource(ssrc) ?: return
        val state = sources[source.primarySSRC] ?: return
        synchronized(state) {
            state.expire(nowMs)
            val pending = state.pending ?: return
            if (pending.observed.putIfAbsent(ssrc, nowMs) != null) {
                return
            }
            /* Whether it answers a request of its own, judged now: a later request must not make it look
             * unrequested. */
            val requestedAt = state.lastRequestedMs[ssrc]
            if (requestedAt != null && nowMs - requestedAt in 0..LATE_ANSWER_WINDOW_MS) {
                pending.answers.add(ssrc)
            }
            if (ssrc in pending.requestedSsrcs) {
                /* The answer, or one of the answers: give the other encodings' keyframes the cluster window to
                 * follow it, regardless of how late it arrived. At most a cluster window past the deadline, since it
                 * arrived before it. */
                pending.deadlineMs = maxOf(pending.deadlineMs, nowMs + CLUSTER_WINDOW_MS)
                state.setDeadline(pending.deadlineMs)
            }
        }
    }

    /**
     * The mode of the sender of the source which has [ssrc] as one of its SSRCs, or [SenderKeyframeMode.UNKNOWN]. It
     * is detected from the observations the media path has closed so far; see [modeOf].
     */
    fun getMode(ssrc: Long): SenderKeyframeMode = modeOf(findSource(ssrc))

    /**
     * The mode of [source]'s sender, or of any sender if [source] is null or unknown. That is the configured mode if
     * any, otherwise the mode detected so far. An observation past its deadline is not closed here, since the
     * encodings' last packet times may have moved on since the deadline. Only the media path closes observations,
     * see [onPacketObserved].
     */
    private fun modeOf(source: MediaSourceDesc?): SenderKeyframeMode {
        fixedMode?.let { return it }
        val state = source?.let { sources[it.primarySSRC] } ?: return SenderKeyframeMode.UNKNOWN
        return state.mode
    }

    /**
     * The primary SSRC of the encoding which has [ssrc] as one of its SSRCs, or [ssrc] itself if none does. This is
     * the key under which requests for the encoding are limited, whichever of its SSRCs a request names.
     */
    fun primarySsrc(ssrc: Long): Long = sourcesBySsrc[ssrc]?.encoding?.primarySSRC ?: ssrc

    /**
     * The primary SSRC of the source which has [ssrc] as one of its SSRCs, or [ssrc] itself if none does. This is
     * the key under which a receiver's own requests for the source are limited, whichever encoding they name.
     */
    fun sourceSsrc(ssrc: Long): Long = sourcesBySsrc[ssrc]?.source?.primarySSRC ?: ssrc

    /**
     * The SSRCs whose source-wide keyframe request limits a request for [ssrc] counts against. For a sender which
     * generates a keyframe on one encoding at a time, just its encoding's primary SSRC, since its requests are
     * independent.
     * Otherwise every encoding of the source, since a request for any encoding costs the whole source a set of
     * keyframes. The limits stay keyed by encoding either way, so that a change of mode changes only how a request is
     * recorded. No limit's history is lost or bypassed.
     */
    fun limiterKeys(ssrc: Long): List<Long> {
        val requested = sourcesBySsrc[ssrc] ?: return listOf(ssrc)
        return if (modeOf(requested.source) == SenderKeyframeMode.PER_ENCODING) {
            listOf(requested.encoding.primarySSRC)
        } else {
            requested.source.rtpEncodings.map { it.primarySSRC }
        }
    }

    /**
     * The SSRCs to send a keyframe request to at [nowMs], for a request meant for every receiver of the source with
     * [ssrc]. Such a request is sent ahead of a dominant speaker change. For a sender which generates a keyframe
     * on one encoding at a time, every encoding being sent. Otherwise one encoding being sent, which will produce
     * them all: [ssrc]'s encoding if it is being sent, else the lowest which is. An encoding which is not being sent
     * would not answer. If none is, [ssrc]'s primary SSRC.
     */
    fun requestSsrcsForSource(ssrc: Long, nowMs: Long): List<Long> {
        val requested = sourcesBySsrc[ssrc] ?: return listOf(ssrc)
        val primary = requested.encoding.primarySSRC
        val live = requested.source.liveEncodingSsrcs(nowMs)
        if (modeOf(requested.source) != SenderKeyframeMode.PER_ENCODING) {
            return listOf(if (primary in live) primary else live.firstOrNull() ?: primary)
        }
        return live.ifEmpty { listOf(primary) }
    }

    /** The state as it is: an observation past its deadline is left for the media path to close, not closed here. */
    fun debugState(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("fixed_mode", fixedMode?.toString() ?: "auto")
        put("open_observations", openSources.size)
        sources.forEach { (primarySsrc, state) ->
            synchronized(state) {
                set<ObjectNode>("source_$primarySsrc", state.debugState())
            }
        }
    }

    /** One or more requests awaiting their answer. */
    private class PendingRequest(requestedSsrc: Long, val requestTimeMs: Long, val liveSsrcs: MutableSet<Long>) {
        /** The SSRCs requested since the observation opened. */
        val requestedSsrcs = linkedSetOf(requestedSsrc)

        /**
         * When the observation closes: a response window after the most recent request which joined it, or a
         * cluster window after the answer, whichever is later.
         */
        var deadlineMs = requestTimeMs + RESPONSE_WINDOW_MS

        /** When a keyframe was first observed on each SSRC since the request. */
        val observed = LinkedHashMap<Long, Long>()

        /** The SSRCs of [observed] whose keyframe followed a request of its own within [LATE_ANSWER_WINDOW_MS]. */
        val answers = HashSet<Long>()
    }

    private inner class SourceState(val primarySsrc: Long) {
        @Volatile
        var mode = SenderKeyframeMode.UNKNOWN
        var pending: PendingRequest? = null

        /** The deadline of [pending], or [Long.MAX_VALUE], readable without the lock for the per-packet sweep. */
        @Volatile
        var pendingDeadlineMs = Long.MAX_VALUE
            private set

        /** Sets [pendingDeadlineMs], and the earliest deadline of all with it; after [openSources] is updated. */
        fun setDeadline(deadlineMs: Long) {
            pendingDeadlineMs = deadlineMs
            updateNextDeadline()
        }

        /** When each SSRC was most recently requested, to recognize a keyframe as answering a request of its own. */
        val lastRequestedMs = HashMap<Long, Long>()
        private var lastEvidence: SenderKeyframeMode? = null
        private var evidenceCount = 0
        private var numClustered = 0
        private var numPerEncoding = 0
        private var numUnanswered = 0
        private var numDiscarded = 0
        private var numInconclusive = 0

        /** Closes and evaluates the pending observation if its response window has passed. */
        fun expire(nowMs: Long) {
            val p = pending ?: return
            if (nowMs > p.deadlineMs) {
                pending = null
                openSources.remove(primarySsrc)
                setDeadline(Long.MAX_VALUE)
                evaluate(p)
            }
        }

        private fun evaluate(p: PendingRequest) {
            /* The answer to the request is the first keyframe on any of the encodings requested. */
            val t0 = p.requestedSsrcs.mapNotNull { p.observed[it] }.minOrNull() ?: run {
                numUnanswered++
                return
            }
            if (p.observed.keys.any { it !in p.liveSsrcs }) {
                numDiscarded++
                return
            }
            /* A keyframe on an unrequested encoding whose current run of frames began after the request is the
             * keyframe with which the encoding resumed. A sender produces that keyframe in either mode. The encoding
             * was live at the request only because the sender signaled it, or it was paused right then. */
            if (p.observed.keys.any { other ->
                    other !in p.requestedSsrcs &&
                        (sourcesBySsrc[other]?.encoding?.liveness?.flowingSinceMs ?: Long.MIN_VALUE) > p.requestTimeMs
                }
            ) {
                numDiscarded++
                return
            }
            /* Only encodings which were not requested count as evidence, and only encodings still being sent after
             * the answer arrived. An encoding which produced no keyframe and no media either may simply have been
             * turned off. Whether it was still sending is read from its last packet time. That is the encoding's
             * state as of the observation's close. The first packet after the deadline closes the observation
             * before it is recorded (see [onPacketObserved]). So an encoding which sent nothing after the answer,
             * and was turned back on later, is not mistaken for an encoding which kept sending. A keyframe which
             * answered a request of its own, made outside this observation, is not evidence either. */
            val others = (p.liveSsrcs - p.requestedSsrcs).filter { other ->
                if (other in p.observed) {
                    other !in p.answers
                } else {
                    (sourcesBySsrc[other]?.encoding?.liveness?.lastPacketReceivedMs ?: Long.MIN_VALUE) > t0
                }
            }
            if (others.isEmpty()) {
                numInconclusive++
                return
            }
            /* A keyframe on an encoding nobody asked for, later than a clustered sender's would arrive. The sender
             * generated it for reasons of its own, in either mode, so it says nothing; and its absence would have
             * been misleading. */
            if (others.any { other -> p.observed[other]?.let { abs(it - t0) > CLUSTER_WINDOW_MS } ?: false }) {
                numDiscarded++
                return
            }
            /* Every keyframe left is within the cluster window of the answer. */
            val clustered = others.count { it in p.observed }
            val evidence = when (clustered) {
                others.size -> SenderKeyframeMode.CLUSTERED

                0 -> SenderKeyframeMode.PER_ENCODING

                else -> {
                    numDiscarded++
                    return
                }
            }
            if (evidence == SenderKeyframeMode.CLUSTERED) numClustered++ else numPerEncoding++
            if (evidence == lastEvidence) {
                evidenceCount++
            } else {
                lastEvidence = evidence
                evidenceCount = 1
            }
            val threshold = if (evidence == SenderKeyframeMode.PER_ENCODING) {
                PER_ENCODING_EVIDENCE_THRESHOLD
            } else {
                EVIDENCE_THRESHOLD
            }
            if (evidenceCount >= threshold && mode != evidence) {
                logger.info("Sender keyframe mode of source $primarySsrc changed from $mode to $evidence")
                mode = evidence
            }
        }

        fun debugState(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
            put("mode", mode.toString())
            put("last_evidence", lastEvidence?.toString() ?: "none")
            put("evidence_count", evidenceCount)
            put("num_clustered", numClustered)
            put("num_per_encoding", numPerEncoding)
            put("num_unanswered", numUnanswered)
            put("num_discarded", numDiscarded)
            put("num_inconclusive", numInconclusive)
            put("pending", pending != null)
        }
    }

    companion object {
        /** How long after a request its answer is awaited. A keyframe takes a round trip plus an encode. */
        const val RESPONSE_WINDOW_MS = 1500L

        /** The longest an observation may stay open as further requests join it. */
        const val MAX_OBSERVATION_MS = 5000L

        /**
         * How long after a request a keyframe on the requested encoding is still taken to be its answer, when it
         * arrives during a later observation. Longer than [RESPONSE_WINDOW_MS], since by then the answer is late.
         */
        const val LATE_ANSWER_WINDOW_MS = 2 * RESPONSE_WINDOW_MS

        /**
         * How long after the answer to a request a keyframe on another encoding still counts as part of the same
         * set. A sender which generates keyframes on every encoding together encodes them within a frame interval.
         * On a constrained uplink, though, a large keyframe is paced out behind the smaller keyframes, so this is
         * generous. It can be generous: a keyframe on an encoding nobody asked for is generated by the sender on
         * its own. The activation check above already rules that case out.
         */
        const val CLUSTER_WINDOW_MS = 1000L

        /** How many consecutive observations must agree before the mode changes. */
        const val EVIDENCE_THRESHOLD = 2

        /**
         * How many consecutive observations must agree before the mode changes to [PER_ENCODING]. More than
         * [EVIDENCE_THRESHOLD], since the evidence for it is a keyframe not seen, which loss can fake, and the cost
         * of being wrong is keyframes.
         */
        const val PER_ENCODING_EVIDENCE_THRESHOLD = 3
    }
}

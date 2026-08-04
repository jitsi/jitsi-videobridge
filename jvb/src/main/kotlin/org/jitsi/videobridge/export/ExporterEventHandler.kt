/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
package org.jitsi.videobridge.export

import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.TranscriptionResultEvent

/**
 * The conference-side operations an [Exporter] (and the [ExporterWrapper] that owns it) depends on: sinks for the
 * events a peer sends back, plus lookups resolving an audio SSRC to conference state. Implemented by the conference
 * and passed in, so the exporter subsystem doesn't depend on the conference directly and can be driven by a fake in
 * tests.
 */
interface ExporterEventHandler {
    /** Handles a transcription result received back from a peer. */
    fun handleTranscriptionResult(event: TranscriptionResultEvent)

    /** Handles a translated-audio media event received back from a peer. */
    fun handleMediaEvent(event: MediaEvent)

    /**
     * Handles a synthetic source's sending-state change, derived from the `start`/`stop` mediajson events a peer sends
     * to bracket a "talk".
     *
     * @param sourceName the synthetic source whose sending state changed.
     * @param sending true at the start of a talk, false at its end.
     * @param timestamp the talk boundary's RTP timestamp (48 kHz); for a stop, one past the end of the run.
     */
    fun handleSendingChange(sourceName: String, sending: Boolean, timestamp: Long)

    /** Resolves an audio SSRC to its source name, used to filter outbound audio by a connect's exports. */
    fun getAudioSourceName(ssrc: Long): String?

    /** Resolves an audio SSRC to whether diarization is requested for its endpoint (colibri2 `diarize` attribute). */
    fun getDiarize(ssrc: Long): Boolean
}

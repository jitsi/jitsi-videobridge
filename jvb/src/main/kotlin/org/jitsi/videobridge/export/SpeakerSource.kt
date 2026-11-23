/*
 * Copyright @ 2026 - Present, 8x8 Inc
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

import org.jitsi.videobridge.AbstractEndpoint

/**
 * An audio source in the order of the recent speakers: the name of the source, and the ID of the endpoint that
 * signaled it. Each exporter selects from this order with the same rule that it uses for audio (see
 * [Exporter.wants]), so it needs the endpoint of each name.
 */
data class SpeakerSource(val sourceName: String, val endpointId: String) {
    companion object {
        /**
         * The own (not synthetic) audio sources of [endpoints] that have a name, in the order of the endpoints.
         */
        @JvmStatic
        fun of(endpoints: List<AbstractEndpoint>): List<SpeakerSource> = endpoints.flatMap { endpoint ->
            endpoint.audioSources.mapNotNull { source ->
                source.sourceName?.takeIf { !source.synthetic }?.let { SpeakerSource(it, endpoint.id) }
            }
        }
    }
}

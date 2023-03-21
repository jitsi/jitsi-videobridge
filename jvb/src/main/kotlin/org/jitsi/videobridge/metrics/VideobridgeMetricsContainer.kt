/*
 * Copyright @ 2022 - present 8x8, Inc.
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
package org.jitsi.videobridge.metrics

import org.jitsi.metrics.MetricsContainer

/**
 * `VideobridgeMetricsContainer` gathers and exports metrics
 * from a [Videobridge][org.jitsi.videobridge.Videobridge] instance.
 */
class VideobridgeMetricsContainer private constructor() : MetricsContainer(namespace = "jitsi_jvb") {

    companion object {
        /**
         * The singleton instance of `MetricsContainer`.
         */
        @JvmStatic
        val instance = VideobridgeMetricsContainer()
    }
}

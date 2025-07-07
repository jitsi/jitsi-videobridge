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
package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.utils.ms
import java.time.Duration

/** Google CC Network Controller Factory,
 * based on WebRTC api/transport/goog_cc_factory.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings, and features not used in
 * Chrome have been removed.
 */

class GoogCcFactoryConfig(
    val feedbackOnly: Boolean = false,
    /* Not in this object in WebRTC: This is a field trial parameter there */
    val rateControlSettings: CongestionWindowConfig = CongestionWindowConfig()
)

class GoogCcNetworkControllerFactory(
    private val factoryConfig: GoogCcFactoryConfig = GoogCcFactoryConfig()
) : NetworkControllerFactoryInterface {
    override fun create(config: NetworkControllerConfig): NetworkControllerInterface {
        val googCcConfig = GoogCcConfig(feedbackOnly = factoryConfig.feedbackOnly)
        return GoogCcNetworkController(config, googCcConfig)
    }

    override fun getProcessInterval(): Duration {
        val kUpdateIntervalMs = 25L
        return kUpdateIntervalMs.ms
    }
}

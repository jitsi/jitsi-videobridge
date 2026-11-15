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

import org.jitsi.nlj.rtp.scream.ScreamNetworkController
import org.jitsi.nlj.rtp.scream.ScreamV2Parameters
import org.jitsi.utils.ms
import java.time.Duration

/* Google CC Network Controller Factory,
 * based on WebRTC api/transport/goog_cc_factory.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * Field trial settings have been generally removed, set to their default settings, and features not used in
 * Chrome have been removed.
 */

/**
 * When ScreamV2 is used in place of Google CC. In WebRTC this is the value of the field trial
 * WebRTC-Bwe-ScreamV2:
 *   Enabled - Scream is used if RFC 8888 congestion control is negotiated.
 *   mode:always - Scream is used regardless of RTCP feedback.
 */
enum class ScreamMode(val configValue: String) {
    GoogCc("never"),
    ScreamWithRfc8888("with-rfc8888"),
    ScreamWithTwccOrRfc8888("always");

    /** Whether Scream is used, given whether RFC 8888 feedback was negotiated. */
    fun usesScream(rfc8888FeedbackNegotiated: Boolean): Boolean = this == ScreamWithTwccOrRfc8888 ||
        (this == ScreamWithRfc8888 && rfc8888FeedbackNegotiated)

    companion object {
        fun fromConfigValue(value: String): ScreamMode = entries.find { it.configValue == value }
            ?: throw IllegalArgumentException(
                "Unrecognized SCReAM mode $value, expected one of ${entries.map { it.configValue }}"
            )
    }
}

class GoogCcFactoryConfig(
    /* Not in this object in WebRTC: This is a field trial parameter there */
    val rateControlSettings: CongestionWindowConfig = CongestionWindowConfig(),
    val rfc8888FeedbackNegotiated: Boolean = false,
    /* Not in this object in WebRTC: This is a field trial there */
    val screamMode: ScreamMode = ScreamMode.GoogCc,
    /* Not in this object in WebRTC: These are field trial parameters there */
    val screamParameters: ScreamV2Parameters = ScreamV2Parameters()
)

class GoogCcNetworkControllerFactory(
    private val factoryConfig: GoogCcFactoryConfig = GoogCcFactoryConfig()
) : NetworkControllerFactoryInterface {
    override fun create(config: NetworkControllerConfig): NetworkControllerInterface {
        val googCcConfig = GoogCcConfig()
        if (factoryConfig.screamMode.usesScream(factoryConfig.rfc8888FeedbackNegotiated)) {
            return ScreamNetworkController(config, factoryConfig.screamParameters)
        }
        return GoogCcNetworkController(config, googCcConfig)
    }

    override fun getProcessInterval(): Duration {
        val kUpdateIntervalMs = 25L
        return kUpdateIntervalMs.ms
    }
}

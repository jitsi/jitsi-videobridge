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
package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerFactoryInterface
import java.time.Duration

/** Test scenario RTP transport config,
 * based on WebRTC call/rtp_transport_config.h in
 * WebRTC tag branch-heads/6422 (Chromium 125).
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */
class RtpTransportConfig {
    // Bitrate config used until valid bitrate estimates are calculated. Also
    // used to cap total bitrate used. This comes from the remote connection.
    var bitrateConfig = BitrateConstraints()

    // Task Queue Factory to be used in this call. Required.
    var taskQueueFactory: TaskQueueFactory? = null

    // Network controller factory to use for this call.
    var networkControllerFactory: NetworkControllerFactoryInterface? = null

    // The burst interval of the pacer, see TaskQueuePacedSender constructor.
    var pacerBurstInterval: Duration? = null

    fun extractTransportConfig(): RtpTransportConfig {
        return RtpTransportConfig().also {
            it.bitrateConfig = bitrateConfig
            it.networkControllerFactory = networkControllerFactory
            it.taskQueueFactory = taskQueueFactory
            it.pacerBurstInterval = pacerBurstInterval
        }
    }
}

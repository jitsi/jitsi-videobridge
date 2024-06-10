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

import java.time.Clock

/** Test scenario call,
 * based on WebRTC call/call.{h,cc} in
 * WebRTC tag branch-heads/6422 (Chromium 125).
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */
abstract class Call {
    class Stats {
        var sendBandwidthBps = 0 // Estimated available send bandwidth.
        var maxPaddingBitrateBps = 0 // Cumulative configured max padding.
        var recvBandwidthBps = 0 // Estimated available receive bandwidth.
        var pacerDealyMs = 0L
        var rttMs = -1L
    }

    // Returns the call statistics, such as estimated send and receive bandwidth,
    // pacing delay, etc.
    abstract fun getStats(): Stats

    companion object {
        fun create(config: CallConfig): Call {
            val clock = Clock.systemUTC()
            return create(
                config,
                clock,
                RtpTransportControllerSendFactory().create(
                    config.extractTransportConfig(),
                    clock
                )
            )
        }
        fun create(
            config: CallConfig,
            clock: Clock,
            transportControllerSend: RtpTransportControllerSendInterface
        ): Call {
            return InternalCall(clock, config, transportControllerSend, config.taskQueueFactory!!)
        }
    }
}

private class InternalCall(
    clock: Clock,
    config: CallConfig,
    transportSend: RtpTransportControllerSendInterface,
    taskQueueFactory: TaskQueueFactory
) : Call() {
    override fun getStats(): Stats {
        TODO("Not yet implemented")
    }
}

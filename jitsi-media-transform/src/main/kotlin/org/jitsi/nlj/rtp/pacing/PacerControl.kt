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
package org.jitsi.nlj.rtp.pacing

import org.jitsi.nlj.rtp.bandwidthestimation2.PacerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.ProbeClusterConfig
import org.jitsi.nlj.util.DataSize
import java.time.Duration

/**
 * The part of a pacer that the code which runs a network controller uses. These are the calls that
 * RtpTransportControllerSend in WebRTC makes to its pacer.
 *
 * The caller can hold its own lock while it calls these methods. An implementation must not wait for a lock that
 * is held while packets are sent, because sending a packet calls back into the caller.
 */
interface PacerControl {
    /** Sets the pacing rate, the padding rate and the burst interval. */
    fun setPacerConfig(config: PacerConfig)

    /** Sets whether the data in flight has reached the congestion window. A congested pacer sends no media. */
    fun setCongested(congested: Boolean)

    /** Adds probe clusters. The pacer sends the probes. */
    fun createProbeClusters(configs: List<ProbeClusterConfig>)

    /** The total size of the packets that are waiting in the pacer. */
    fun queueSizeData(): DataSize

    /** The time it takes to send the packets that are waiting in the pacer, at the current pacing rate. */
    fun expectedQueueTime(): Duration
}

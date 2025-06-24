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

import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import java.time.Duration

/** Base type for network controller,
 * based on WebRTC api/transport/network_control.h in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

/** Configuration sent to factory create function. The parameters here are
 * optional to use for a network controller implementation. */
class NetworkControllerConfig(
    val parentLogger: Logger,
    val diagnosticContext: DiagnosticContext,

    /** The initial constraints to start with, these can be changed at any later
     * time by calls to OnTargetRateConstraints. Note that the starting rate
     * has to be set initially to provide a starting state for the network
     * controller, even though the field is marked as nullable.
     */
    val constraints: TargetRateConstraints = TargetRateConstraints(),

    /** Initial stream specific configuration, these are changed at any later time
     * by calls to OnStreamsConfig.
     */
    val streamBasedConfig: StreamsConfig = StreamsConfig()
)

/** NetworkControllerInterface is implemented by network controllers. A network
 * controller is a class that uses information about network state and traffic
 * to estimate network parameters such as round trip time and bandwidth. Network
 * controllers does not guarantee thread safety, the interface must be used in a
 * non-concurrent fashion.
 */
interface NetworkControllerInterface {
    /** Called when network availabilty changes */
    fun onNetworkAvailability(msg: NetworkAvailability): NetworkControlUpdate

    /** Called when the receiving or sending endpoint changes address.*/
    fun onNetworkRouteChange(msg: NetworkRouteChange): NetworkControlUpdate

    /** Called periodically with a periodicy as specified by
     * [NetworkControllerFactoryInterface.getProcessInterval].
     */
    fun onProcessInterval(msg: ProcessInterval): NetworkControlUpdate

    /** Called when remotely calculated bitrate is received. */
    fun onRemoteBitrateReport(msg: RemoteBitrateReport): NetworkControlUpdate

    /** Called round trip time has been calculated by protocol specific mechanisms. */
    fun onRoundTripTimeUpdate(msg: RoundTripTimeUpdate): NetworkControlUpdate

    /** Called when a packet is sent on the network. */
    fun onSentPacket(sentPacket: SentPacket): NetworkControlUpdate

    // Omitted: onReceivedPacket: Not used

    /** Called when the stream specific configuration has been updated. */
    fun onStreamsConfig(msg: StreamsConfig): NetworkControlUpdate

    /** Called when target transfer rate constraints has been changed. */
    fun onTargetRateConstraints(constraints: TargetRateConstraints): NetworkControlUpdate

    /** Called when a protocol specific calculation of packet loss has been made. */
    fun onTransportLossReport(msg: TransportLossReport): NetworkControlUpdate

    /** Called with per packet feedback regarding receive time. */
    fun onTransportPacketsFeedback(report: TransportPacketsFeedback): NetworkControlUpdate

    // Omitted: onNetworkStateUpdate: Not used
}

/** NetworkControllerFactoryInterface is an interface for creating a network
 * controller. */
interface NetworkControllerFactoryInterface {
    /** Used to create a new network controller, requires an observer to be
     * provided to handle callbacks.
     */
    fun create(config: NetworkControllerConfig): NetworkControllerInterface

    /** Returns the interval by which the network controller expects
     * OnProcessInterval calls.
     */
    fun getProcessInterval(): Duration
}

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

import java.net.InetAddress

/** Test scenario network emulation interfaces,
 * based on WebRTC api/test/network_emulation/network_emulation_interfaces.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

// Interface for handling IP packets from an emulated network. This is used with
// EmulatedEndpoint to receive packets on a specific port.
interface EmulatedNetworkReceiverInterface

// EmulatedEndpoint is an abstraction for network interface on device. Instances
// of this are created by NetworkEmulationManager::CreateEndpoint and
// thread safe.
interface EmulatedEndpoint : EmulatedNetworkReceiverInterface {
    fun bindReceiver(desiredPort: Short, receiver: EmulatedNetworkReceiverInterface): Short?
    fun getPeerLocalAddress(): InetAddress
}

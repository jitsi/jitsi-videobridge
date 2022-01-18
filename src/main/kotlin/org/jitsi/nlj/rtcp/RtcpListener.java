/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtcp;

import org.jetbrains.annotations.*;
import org.jitsi.rtp.rtcp.*;

import java.time.*;

/**
 * NOTE(brian): Java doesn't see default implementation interface methods when
 * written from kotlin, so this is implemented in java (which works for both
 * kotlin and java) because I got tired of adding no-op methods for one of
 * these methods as most listeners care about one but not the other
 */
public interface RtcpListener {
    default void rtcpPacketReceived(RtcpPacket packet, @Nullable Instant receivedTime) {}
    default void rtcpPacketSent(RtcpPacket packet) {}
}


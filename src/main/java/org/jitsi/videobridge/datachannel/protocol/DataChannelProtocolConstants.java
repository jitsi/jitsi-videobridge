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

package org.jitsi.videobridge.datachannel.protocol;

public class DataChannelProtocolConstants
{
    /**
     * The <tt>String</tt> value of the <tt>Protocol</tt> field of the
     * <tt>DATA_CHANNEL_OPEN</tt> message.
     */
    public static final String PROTOCOL_STRING = "http://jitsi.org/protocols/colibri";

    /**
     * DataChannel message types
     //TODO: enum?
     */
    public static final int MSG_TYPE_CHANNEL_OPEN = 0x3;
    public static final int MSG_TYPE_CHANNEL_ACK = 0x2;

    /**
     * Channel types
     * TODO: enum?
     */
    public static final int RELIABLE = 0x00;
    public static final int RELIABLE_UNORDERED = 0x80;
    public static final int PARTIAL_RELIABLE_REXMIT = 0x01;
    public static final int PARTIAL_RELIABLE_REXMIT_UNORDERED = 0x81;
    public static final int PARTIAL_RELIABLE_TIMED = 0x02;
    public static final int PARTIAL_RELIABLE_TIMED_UNORDERED = 0x82;

    /**
     * This is the SCTP PPID used for datachannel establishment protocol
     * https://tools.ietf.org/html/draft-ietf-rtcweb-data-protocol-08#section-8.1
     */
    public static final int WEBRTC_DCEP_PPID = 50;

    /**
     * Payload protocol id that identifies text data UTF8 encoded in WebRTC data
     * channels.
     */
    public static final int WEBRTC_PPID_STRING = 51;

    /**
     * Payload protocol id that identifies binary data in WebRTC data channel.
     */
    public static final int WEBRTC_PPID_BIN = 53;
}

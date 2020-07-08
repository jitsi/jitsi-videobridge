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

package org.jitsi.videobridge.datachannel;

import java.nio.*;

/**
 * TODO: This needs documentation
 *
 * @author Brian Baldino
 */
public interface Transport
{
    interface DataChannelDataCallback
    {
        /**
         * A packet was received.
         */
        void dataChannelPacketReceived(ByteBuffer data, int sid, int ppid);
    }

    interface DataChannelTransportEventHandler
    {
        /**
         * The transport connected.
         */
        void transportConnected();

        /**
         * The transport disconnected.
         */
        void transportDisconnected();
    }

    /**
     * Sets the handler for data.
     * @param dataChannelDataCallback
     */
    void onData(DataChannelDataCallback dataChannelDataCallback);

    /**
     * Sends a {@link ByteBuffer}.
     */
    int send(ByteBuffer data, boolean ordered, int sid, int ppid);

    /**
     * Sets the handler for events.
     */
    void onEvent(
            DataChannelTransportEventHandler dataChannelTransportEventHandler);
}

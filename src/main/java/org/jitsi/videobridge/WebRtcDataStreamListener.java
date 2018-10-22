/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge;

/**
 * Interface used to notify about WebRTC data channels opened by
 * {@link SctpConnection}.
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
 */
public interface WebRtcDataStreamListener
{
    /**
     * Fired when new WebRTC data channel is opened.
     *
     * NOTE It's important that the listener will not do any blocking operations
     *
     * @param source the <tt>SctpConnection</tt> which is the source of the
     * event
     * @param channel the <tt>WebRtcDataStream</tt> that represents opened
     * WebRTC data channel.
     */
    default void onChannelOpened(
            SctpConnection source,
            WebRtcDataStream channel)
    {
    }
}

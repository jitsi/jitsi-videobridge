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
package org.jitsi.videobridge.ratecontrol;

import org.jitsi.videobridge.*;

import java.util.*;

/**
 * @author George Politis
 */
public interface BitrateController
{
    /**
     * Notifies this instance that the <tt>RTPTranslator</tt> that it uses will
     * write a specific packet/<tt>buffer</tt> from a specific
     * <tt>RtpChannel</tt>. Allows this instance to apply some bitrate control
     * logic and disallow the translation of the specified packet from the
     * specified source <tt>RtpChannel</tt> into this destination
     * <tt>RtpChannel</tt>.
     *
     * @param data
     * @param buf
     * @param off
     * @param len
     * @param source
     * @return <tt>true</tt> to allow the <tt>RTPTranslator</tt> to write the
     * specified packet/<tt>buffer</tt> into this <tt>Channel</tt>; otherwise,
     * <tt>false</tt>
     */
    boolean rtpTranslatorWillWrite(
        boolean data, byte[] buf, int off, int len, RtpChannel source);

    /**
     * @return the maximum number of endpoints whose video streams will be
     * forwarded to the endpoint. A value of {@code -1} means that there is no
     * limit.
     */
    int getLastN();

    /**
     * Sets the value of {@code lastN}, that is, the maximum number of endpoints
     * whose video streams will be forwarded to the endpoint. A value of
     * {@code -1} means that there is no limit.
     * @param lastN the value to set.
     */
    void setLastN(int lastN);

    /**
     * Checks whether RTP packets from {@code sourceChannel} should be forwarded
     * to the {@link RtpChannel} attached to this instance.
     * @param sourceChannel the channel.
     * @return {@code true} iff RTP packets from {@code sourceChannel} should
     * be forwarded to the {@link RtpChannel} attached to this instance.
     */
    boolean isForwarded(Channel sourceChannel);

    /**
     * Sets the list of "pinned" endpoints (i.e. endpoints for which video
     * should always be forwarded, regardless of {@code lastN}).
     * @param newPinnedEndpointIds the list of endpoint IDs to set.
     */
    void setPinnedEndpointIds(List<String> newPinnedEndpointIds);

    /**
     * Sets the list of "selected" endpoints (i.e. endpoints for which the video
     * should be optimized).
     *
     * @param newSelectedEndpointIds the list of endpoint IDs to set.
     */
    void setSelectedEndpointIds(List<String> newSelectedEndpointIds);

    /**
     * Notifies this instance that the ordered list of endpoints in the
     * conference has changed.
     *
     * @param endpoints the new ordered list of endpoints in the conference.
     * @return the list of endpoints which were added to the list of forwarded
     * endpoints as a result of the call, or {@code null} if none were added.
     */
    List<Endpoint> speechActivityEndpointsChanged(List<Endpoint> endpoints);

    /**
     *
     * @param rtpChannel
     */
    void rtpEncodingParametersChanged(RtpChannel rtpChannel);

    /**
     * Gets the optimal bitrate (in bps) that is required to stream the good
     * stuff.
     *
     * @return the optimal bitrate (in bps) that is required to stream the good
     * stuff.
     */
    long getOptimalBitrateBps();
}

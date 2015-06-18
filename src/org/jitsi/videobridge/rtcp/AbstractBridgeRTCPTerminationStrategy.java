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
package org.jitsi.videobridge.rtcp;

import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.videobridge.*;

/**
 * @author George Politis
 */
public abstract class AbstractBridgeRTCPTerminationStrategy
    extends AbstractRTCPTerminationStrategy
{
    private Conference conference;

    /**
     * Sets the <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     *
     * @param conference The <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     */
    public void setConference(Conference conference)
    {
        this.conference = conference;
    }

    /**
     * Gets the <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     *
     * @return The <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     */
    public Conference getConference()
    {
        return conference;
    }
}

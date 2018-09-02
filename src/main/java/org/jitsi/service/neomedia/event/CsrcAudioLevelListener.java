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
package org.jitsi.service.neomedia.event;

/**
 * The <tt>CsrcAudioLevelListener</tt> delivers audio level events reported by
 * the remote party in cases where it (the remote party) is acting as a mixer,
 * mixing flows from numerous contributors. It is up to upper layers such as
 * SIP to define means of determining the exact members that the CSRC IDs and
 * hence audio levels participants belong to.
 *
 * @author Emil Ivov
 */
public interface CsrcAudioLevelListener
{
    /**
     * Called by the media service implementation after it has received audio
     * levels for the various participants (Contributing SouRCes) that are
     * taking part in a conference call.
     *
     * @param audioLevels a <tt>long</tt> array in which the elements at the
     * even indices specify the CSRC IDs and the elements at the odd indices
     * specify the respective audio levels
     */
    public void audioLevelsReceived(long[] audioLevels);
}

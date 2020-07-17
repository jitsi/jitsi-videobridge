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

package org.jitsi.videobridge.ice;

import org.ice4j.ice.*;

public class TransportUtils
{
    /**
     * Determines whether at least one <tt>LocalCandidate</tt> of a specific ICE
     * <tt>Component</tt> can reach (in the terms of the ice4j library) a
     * specific <tt>RemoteCandidate</tt>
     *
     * @param component the ICE <tt>Component</tt> which contains the
     * <tt>LocalCandidate</tt>s to check whether at least one of them can reach
     * the specified <tt>remoteCandidate</tt>
     * @param remoteCandidate the <tt>RemoteCandidate</tt> to check whether at
     * least one of the <tt>LocalCandidate</tt>s of the specified
     * <tt>component</tt> can reach it
     * @return <tt>true</tt> if at least one <tt>LocalCandidate</tt> of the
     * specified <tt>component</tt> can reach the specified
     * <tt>remoteCandidate</tt>
     */
    public static boolean canReach(
            Component component,
            RemoteCandidate remoteCandidate)
    {
        return component.getLocalCandidates().stream().
                anyMatch(
                        localCandidate -> localCandidate.canReach(remoteCandidate));
    }
}

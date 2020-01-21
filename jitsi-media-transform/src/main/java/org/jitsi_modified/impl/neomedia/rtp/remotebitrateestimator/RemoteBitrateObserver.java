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
package org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator;

import java.util.*;

/**
 * <tt>RemoteBitrateObserver</tt> is used to signal changes in bitrate estimates
 * for the incoming streams.
 *
 * webrtc/modules/remote_bitrate_estimator/include/remote_bitrate_estimator.h
 *
 * @author Lyubomir Marinov
 */
public interface RemoteBitrateObserver
{
    /**
     * Called when a receive channel group has a new bitrate estimate for the
     * incoming streams.
     *
     * @param ssrcs
     * @param bitrate
     */
    void onReceiveBitrateChanged(Collection<Long> ssrcs, long bitrate);
}

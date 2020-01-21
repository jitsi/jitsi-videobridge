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

/**
 * webrtc/modules/remote_bitrate_estimator/include/bwe_defines.h
 *
 * @author Lyubomir Marinov
 */
class RateControlInput
{
    public BandwidthUsage bwState;

    public long incomingBitRate;

    public double noiseVar;

    public RateControlInput(
            BandwidthUsage bwState,
            long incomingBitRate,
            double noiseVar)
    {
        this.bwState = bwState;
        this.incomingBitRate = incomingBitRate;
        this.noiseVar = noiseVar;
    }

    /**
     * Assigns the values of the fields of <tt>source</tt> to the respective
     * fields of this <tt>RateControlInput</tt>.
     *
     * @param source the <tt>RateControlInput</tt> the values of the fields of
     * which are to be assigned to the respective fields of this
     * <tt>RateControlInput</tt>
     */
    public void copy(RateControlInput source)
    {
        bwState = source.bwState;
        incomingBitRate = source.incomingBitRate;
        noiseVar = source.noiseVar;
    }
}

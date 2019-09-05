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
package org.jitsi_modified.service.neomedia.rtp;

import org.jitsi.nlj.rtcp.*;
import org.jitsi.nlj.stats.*;

/**
 * @author Boris Grozev
 */
public interface BandwidthEstimator
        extends EndpointConnectionStats.EndpointConnectionStatsListener, RtcpListener
{
    /**
     * Adds a listener to be notified about changes to the bandwidth estimation.
     * @param listener
     */
    void addListener(Listener listener);

    /**
     * Removes a listener.
     * @param listener
     */
    void removeListener(Listener listener);

    /**
     * @return the latest estimate.
     */
    long getLatestEstimate();

    /**
     * @return the latest values of the Receiver Estimated Maximum Bandwidth.
     */
    long getLatestREMB();

    /**
     * @return the {@link Statistics} specific to this bandwidth estimator.
     */
    Statistics getStatistics();

    /**
     * void SendSideBandwidthEstimation::UpdateReceiverEstimate
     * This is the entry/update point for the estimated bitrate in the
     * REMBPacket or a Delay Based Controller estimated bitrate when the
     * Delay based controller and the loss based controller lives on the
     * send side. see internet draft on "Congestion Control for RTCWEB"
     */
    void updateReceiverEstimate(long bandwidth);

    /**
     * @return the latest effective fraction loss calculated by this
     * {@link BandwidthEstimator}. The value is between 0 and 256 (corresponding
     * to 0% and 100% respectively).
     */
    int getLatestFractionLoss();

    interface Listener
    {
        void bandwidthEstimationChanged(long newValueBps);
    }

    /**
     * Holds stats specific to the bandwidth estimator.
     */
    interface Statistics
    {
        /**
         * @return the number of millis spent in the loss-degraded state.
         */
        long getLossDegradedMs();

        /**
         * @return the number of millis spent in the loss-limited state.
         */
        long getLossLimitedMs();

        /**
         * @return the number of millis spent in the loss-free state.
         */
        long getLossFreeMs();

        void update(long nowMs);
    }
}



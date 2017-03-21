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
package org.jitsi.videobridge.cc;

/**
 * @author George Politis
 */
public interface PaddingParams
{
    /**
     * Gets
     *
     * @return the current bitrate (bps).
     */
    Bitrates getBitrates();

    /**
     * Gets the SSRC to protect with RTX, in case the padding budget is
     * positive.
     *
     * @return the SSRC to protect with RTX, in case the padding budget is
     * positive.
     */
    long getTargetSSRC();

    /**
     * Utility class that holds the optimal and the current bitrate (in bps).
     */
    class Bitrates
    {
        /**
         * A static bitrates object with 0 bps for both the optimal and the
         * current bitrates.
         */
        static final Bitrates EMPTY = new Bitrates(0, 0);

        /**
         * Ctor.
         *
         * @param currentBps the current bitrate (in bps)
         * @param optimalBps the optimal bitrate (in bps)
         */
        Bitrates(long currentBps, long optimalBps)
        {
            this.currentBps = currentBps;
            this.optimalBps = optimalBps;
        }

        /**
         * The current bitrate (bps). Together with the optimal bitrate, it
         * allows to calculate the probing bitrate.
         */
        long currentBps;

        /**
         * The optimal bitrate (bps). Together with the current bitrate, it
         * allows to calculate the probing bitrate.
         */
        long optimalBps;

        /**
         * Gets the current bitrate (bps). Together with the optimal bitrate, it
         * allows to calculate the probing bitrate.
         *
         * @return the current bitrate (bps).
         */
        long getCurrentBps()
        {
            return currentBps;
        }

        /**
         * Gets the optimal bitrate (bps). Together with the current bitrate, it
         * allows to calculate the probing bitrate.
         *
         * @return the optimal bitrate (bps).
         */
        long getOptimalBps()
        {
            return optimalBps;
        }
    }
}

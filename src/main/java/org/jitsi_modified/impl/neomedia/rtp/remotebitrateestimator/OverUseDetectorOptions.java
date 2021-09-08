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

import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.config.OveruseDetectorConfig;

/**
 * Bandwidth over-use detector options.  These are used to drive experimentation
 * with bandwidth estimation parameters.
 *
 * webrtc/common_types.h
 *
 * @author Lyubomir Marinov
 */
class OverUseDetectorOptions
{
    public double initialAvgNoise = 0.0D;

    public final double[][] initialE
        = new double[][] { { 100, 0 }, { 0, 1e-1 } };

    public double initialOffset = 0.0D;

    public final double[] initialProcessNoise = new double[] { 1e-10, 1e-2 };

    public double initialSlope = 8.0D / 512.0D;

    public double initialThreshold = OveruseDetectorConfig.Companion.getInitialThreshold();

    public double initialVarNoise = 50.0D;
}

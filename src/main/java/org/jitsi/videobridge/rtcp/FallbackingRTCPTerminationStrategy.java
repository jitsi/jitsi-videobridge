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

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 * This RTCP termination strategy delegates to an "active" RTCP
 * termination strategy. The "active" RTCP termination strategy is determined
 * by the number of <tt>Endpoint</tt>s in a <tt>Conference</tt>. The default
 * fallback RTCP termination strategy is the
 * <tt>SilentBridgeRTCPTerminationStrategy</tt>. The default main RTCP
 * termination strategy is the <tt>BasicRTCPTerminationStrategy</tt>.
 *
 * @author George Politis
 */
public class FallbackingRTCPTerminationStrategy
    extends VideoChannelRTCPTerminationStrategy
{
    /**
     * The <tt>Logger</tt> used by the
     * <tt>FallbackingRTCPTerminationStrategy</tt> class and its instances to
     * print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(FallbackingRTCPTerminationStrategy.class);

    /**
     * The name of the property which specifies the FQN name of the RTCP
     * strategy to use when there are less than 3 participants.
     */
    public static final String FALLBACK_STRATEGY_PNAME
        = FallbackingRTCPTerminationStrategy.class.getName()
            + ".fallbackStrategy";

    /**
     * The name of the property which specifies the FQN name of the RTCP
     * strategy to use when there are less than 3 participants.
     */
    public static final String MAIN_STRATEGY_PNAME
        = FallbackingRTCPTerminationStrategy.class.getName() +  ".mainStrategy";

    /**
     * Determines the number of participants needed for the RTCP termination
     * strategy to switch to the main one.
     */
    private static final int PARTICIPANTS_THRESHOLD = 3;

    /**
     * The fallback <tt>RTCPTerminationStrategy</tt> that is to be used when < 3
     * participants are in the conference.
     */
    private RTCPTerminationStrategy fallbackRTCPTerminationStrategy;

    /**
     * The main <tt>RTCPTerminationStrategy</tt> that is to be used when >= 3
     * participants are in the conference.
     */
    private RTCPTerminationStrategy mainRTCPTerminationStrategy;

    //#region Private methods

    /**
     * Sets the {@link this.mainRTCPTerminationStrategy} and
     * {@link this.fallbackRTCPTerminationStrategy} from the configuration.
     */
    public void initialize(VideoChannel vc)
    {
       super.initialize(vc);

        ConfigurationService configurationService
            = vc.getContent().getConference().getVideobridge()
                .getConfigurationService();

        if (configurationService == null)
        {
            return;
        }

        // Initialize {@link this.fallbackRTCPTerminationStrategy} from the
        // configuration.
        String fallbackFQN = configurationService.getString(
            FALLBACK_STRATEGY_PNAME, "");

        if (!StringUtils.isNullOrEmpty(fallbackFQN))
        {
            try
            {
                Class<?> clazz = Class.forName(fallbackFQN);
                RTCPTerminationStrategy fallbackRTCPTerminationSrategy
                    = (RTCPTerminationStrategy) clazz.newInstance();

                this.fallbackRTCPTerminationStrategy
                    = fallbackRTCPTerminationSrategy;
            }
            catch (Exception e)
            {
                logger.error(
                    "Failed to configure the fallback RTCP termination " +
                        "strategy", e);
            }
        }
        else
        {
            this.fallbackRTCPTerminationStrategy
                = new SilentBridgeRTCPTerminationStrategy();
        }

        // Initialize the RTCP termination strategy.
        if (fallbackRTCPTerminationStrategy != null)
        {
            if (fallbackRTCPTerminationStrategy
                instanceof MediaStreamRTCPTerminationStrategy)
            {
                ((MediaStreamRTCPTerminationStrategy)
                    fallbackRTCPTerminationStrategy)
                        .initialize(vc.getStream());
            }
            else if (fallbackRTCPTerminationStrategy
                instanceof VideoChannelRTCPTerminationStrategy)
            {
                ((VideoChannelRTCPTerminationStrategy)
                    fallbackRTCPTerminationStrategy)
                        .initialize(vc);
            }

        }

        // Initialize {@link this.mainRTCPTerminationStrategy} from the
        // configuration.
        String mainRTCPTerminationStrategyFQN = configurationService.getString(
            MAIN_STRATEGY_PNAME, "");

        if (!StringUtils.isNullOrEmpty(mainRTCPTerminationStrategyFQN))
        {
            try
            {
                Class<?> clazz = Class.forName(mainRTCPTerminationStrategyFQN);
                RTCPTerminationStrategy mainRTCPTerminationStrategy
                    = (RTCPTerminationStrategy) clazz.newInstance();

                this.mainRTCPTerminationStrategy = mainRTCPTerminationStrategy;
            }
            catch (Exception e)
            {
                logger.error(
                    "Failed to configure the main RTCP termination strategy",
                    e);
            }
        }
        else
        {
            this.mainRTCPTerminationStrategy
                = new BasicRTCPTerminationStrategy();
        }

        // Initialize the RTCP termination strategy.
        if (this.mainRTCPTerminationStrategy != null)
        {
            if (mainRTCPTerminationStrategy
                instanceof MediaStreamRTCPTerminationStrategy)
            {
                ((MediaStreamRTCPTerminationStrategy)
                    mainRTCPTerminationStrategy)
                        .initialize(vc.getStream());
            }
            else if (mainRTCPTerminationStrategy
                instanceof VideoChannelRTCPTerminationStrategy)
            {
                ((VideoChannelRTCPTerminationStrategy)
                    mainRTCPTerminationStrategy)
                        .initialize(vc);
            }

        }
    }

    /**
     * Returns a <tt>boolean</tt> indicating whether to use the fallback or the
     * main <tt>RTCPTerminationStrategy</tt>.
     *
     * @return
     */
    private boolean fallback()
    {
        VideoChannel vc = getVideoChannel();

        return (vc != null && fallbackRTCPTerminationStrategy != null
            && vc.getContent()
                .getConference().getEndpointCount() < PARTICIPANTS_THRESHOLD);
    }

    //#endregion

    //#region TransformEngineWrapper implementation

    public RTCPTerminationStrategy getActiveRTCPTerminationStrategy()
    {
        return fallback() ? fallbackRTCPTerminationStrategy
            : mainRTCPTerminationStrategy;
    }

    //#endregion

    //#region Settable implementation

    public PacketTransformer getRTPTransformer()
    {
        return getActiveRTCPTerminationStrategy().getRTPTransformer();
    }

    public PacketTransformer getRTCPTransformer()
    {
        return getActiveRTCPTerminationStrategy().getRTCPTransformer();
    }

    //#endregion
}

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

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class VideoChannelLastNAdaptor
        implements BitrateAdaptor
{
    /**
     * Whether the values for the constants have been initialized or not.
     */
    private static boolean configurationInitialized = false;

    /**
     * The <tt>Logger</tt> used by the <tt>VideoChannelLastNAdaptor</tt> class
     * and its instances to print debug information.
     */
    private static final org.jitsi.util.Logger logger
            = org.jitsi.util.Logger.getLogger(VideoChannelLastNAdaptor.class);

    /**
     * The maximum amount of time in milliseconds to keep lastN=0.
     */
    private static int MAX_STAY_AT_ZERO_MS = 60000;

    /**
     * The name of the property which can be used to control the
     * <tt>MAX_STAY_AT_ZERO_MS</tt> constant.
     */
    private static final String MAX_STAY_AT_ZERO_MS_PNAME
            = VideoChannelLastNAdaptor.class.getName() + ".MAX_STAY_AT_ZERO_MS";

    /**
     * The minimum number of endpoints that may fit in the adaptive last n
     * window before switching to low quality for all the receiving
     * participants.
     */
    private static int MIN_ENDPOINTS_BEFORE_HQ_DROP = 2;

    /**
     * The name of the property which can be used to control the
     * <tt>MIN_ENDPOINTS_BEFORE_HQ_DROP_PNAME</tt> constant.
     */
    private static final String MIN_ENDPOINTS_BEFORE_HQ_DROP_PNAME
            = VideoChannelLastNAdaptor.class.getName()
            + ".MIN_ENDPOINTS_BEFORE_HQ_DROP";

    /**
     * The <tt>LastNBitrateController</tt> of this <tt>VideoChannelLastNAdaptor</tt>.
     */
    private final LastNBitrateController bitrateController;

    /**
     * Whether this <tt>VideoChannelLastNAdaptor</tt> has changed the value of
     * lastN at least once already.
     */
    private boolean initialLastNSet = false;

    /**
     * The last time that lastN was non-zero.
     */
    private long lastNonZeroLastN = -1;

    /**
     * The slave <tt>SimulcastAdaptor</tt> of this
     * <tt>VideoChannelLastNAdaptor</tt>.
     */
    private SimulcastAdaptor slaveSimulcastAdaptor;

    /**
     * Initializes a new <tt>VideoChannelLastNAdaptor</tt> instance.
     *
     * @param bitrateController the <tt>LastNBitrateController</tt> for which the
     * new instance is to serve.
     */
    public VideoChannelLastNAdaptor(LastNBitrateController bitrateController)
    {
        this.bitrateController = bitrateController;

        if (bitrateController.getLastNController().getAdaptiveSimulcast())
        {
            this.slaveSimulcastAdaptor
                    = new SimulcastAdaptor(bitrateController);
        }

        this.initializeConfiguration();
    }

    @Override
    public boolean decrease()
    {
        VideoChannel channel = bitrateController.getChannel();
        Endpoint thisEndpoint = channel.getEndpoint();
        int lastN = bitrateController.getLastNController().getCurrentLastN();
        int numEndpointsThatFitIn
                = bitrateController.calcNumEndpointsThatFitIn();

        boolean decreased = false;

        // If there isn't enough bandwidth to fit one high quality from
        // the selected participant and one low quality from the peer,
        // then drop the high quality stream from the selected
        // participant before completely dropping the stream of the
        // other peer.
        if (numEndpointsThatFitIn <= MIN_ENDPOINTS_BEFORE_HQ_DROP
                && slaveSimulcastAdaptor != null
                && slaveSimulcastAdaptor.decrease())
        {
            numEndpointsThatFitIn
                    = bitrateController.calcNumEndpointsThatFitIn();

            decreased = true;
        }

        if (numEndpointsThatFitIn < lastN)
        {
            // Decrease aggressively
            int newn = Math
                    .min(numEndpointsThatFitIn - 1, lastN / 2);

            if (newn < 0)
                newn = 0;

            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<>(4);
                map.put("self", thisEndpoint);
                map.put("numEndpointsThatFitIn", numEndpointsThatFitIn);
                map.put("lastN", lastN);
                map.put("newN", newn);
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("The uplink between the bridge " +
                        "and {self.id} currently receives " +
                        "{receivingEndpointsCount} but it can only support " +
                        "{numEndpointsThatFitIn}. Aggressively reducing lastN" +
                        " to {newN}."));
            }

            bitrateController.getLastNController().setCurrentLastN(newn);

            decreased = true;
        }

        return decreased;
    }

    @Override
    public boolean increase()
    {
        VideoChannel channel = bitrateController.getChannel();
        Endpoint thisEndpoint = channel.getEndpoint();
        int lastN = bitrateController.getLastNController().getCurrentLastN();
        int numEndpointsThatFitIn
                = bitrateController.calcNumEndpointsThatFitIn();

        boolean increased = false;

        // If there is enough bandwidth to fit one high quality
        // from the selected participant and one low quality from the
        // peer, then remove the override.
        if (numEndpointsThatFitIn > MIN_ENDPOINTS_BEFORE_HQ_DROP
                && slaveSimulcastAdaptor != null
                && slaveSimulcastAdaptor.increase())
        {
            numEndpointsThatFitIn
                    = bitrateController.calcNumEndpointsThatFitIn();

            increased = true;
        }

        if (numEndpointsThatFitIn > lastN)
        {
            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<>(4);
                map.put("self", thisEndpoint);
                map.put("numEndpointsThatFitIn", numEndpointsThatFitIn);
                map.put("lastN", lastN);
                map.put("newN", lastN + 1);
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("The uplink between the bridge " +
                        "and {self.id} receives {receivingEndpointsCount} " +
                        "but it can support {numEndpointsThatFitIn}. " +
                        "Conservatively increasing lastN to {newN}."));
            }

            // Increase conservatively, by 1
            bitrateController.getLastNController().setCurrentLastN(lastN + 1);

            increased = true;
        }

        return increased;
    }

    private void initializeConfiguration()
    {
        synchronized (VideoChannelLastNAdaptor.class)
        {
            if (configurationInitialized)
                return;
            configurationInitialized = true;

            VideoChannel channel = bitrateController.getChannel();
            ConfigurationService cfg
                    = ServiceUtils.getService(
                    channel.getBundleContext(),
                    ConfigurationService.class);

            if (cfg != null)
            {
                MIN_ENDPOINTS_BEFORE_HQ_DROP
                        = cfg.getInt(
                        MIN_ENDPOINTS_BEFORE_HQ_DROP_PNAME,
                        MIN_ENDPOINTS_BEFORE_HQ_DROP);

                MAX_STAY_AT_ZERO_MS
                        = cfg.getInt(
                        MAX_STAY_AT_ZERO_MS_PNAME,
                        MAX_STAY_AT_ZERO_MS);
            }
        }
    }

    /**
     * Sets the initial value of lastN.
     *
     * @param lastN The current value of lastN.
     * @return the new value of lastN.
     */
    private int setInitialLastN(int lastN)
    {
        VideoChannel channel = bitrateController.getChannel();
        int endpointCount
            = bitrateController
                    .getLastNController().getForwardedEndpoints().size();

         // We update lastN if either:
         // 1. It is currently disabled (-1)
         // 2. It is currently more than the number of endpoints (because
         // otherwise we detect this as a drop in the number of endpoint the
         // channel can receive and we drop it aggressively)
         //
         // In the other cases (0 <= lastN <= endpointCount) we leave it as it is
         // because it is a reasonable start point.
        if (lastN < 0 || lastN > endpointCount)
        {
            lastN = endpointCount;
            bitrateController.getLastNController()
                    .setCurrentLastN(endpointCount);
        }

        return lastN;
    }

    @Override
    public boolean touch()
    {
        long now = System.currentTimeMillis();

        VideoChannel channel = bitrateController.getChannel();

        // The current value of lastN
        int lastN = channel.getLastN();

        if (lastN > 0)
            lastNonZeroLastN = now;

        if (!initialLastNSet)
        {
            lastN = setInitialLastN(lastN);
            initialLastNSet = true;
        }

        if (lastN == 0
                && lastNonZeroLastN != -1
                && now - lastNonZeroLastN > MAX_STAY_AT_ZERO_MS)
        {
            bitrateController.getLastNController().setCurrentLastN(1);
            return false;
        }

        // XXX(gp) in the future, we may want to touch the slave simulcast
        // adaptor here.

        return true;
    }
}

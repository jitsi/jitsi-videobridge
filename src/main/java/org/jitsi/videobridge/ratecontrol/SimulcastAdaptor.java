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

import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;

import java.util.*;

/**
 * @author George Politis
 */
public class SimulcastAdaptor
        implements BitrateAdaptor
{
    private final LastNBitrateController bitrateController;

    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastAdaptor</tt> class
     * and its instances to print debug information.
     */
    private static final org.jitsi.util.Logger logger
        = org.jitsi.util.Logger.getLogger(SimulcastAdaptor.class);

    public SimulcastAdaptor(LastNBitrateController bitrateController)
    {
        this.bitrateController = bitrateController;
    }

    public boolean touch()
    {
        return true;
    }

    public boolean increase()
    {
        VideoChannel channel = bitrateController.getChannel();
        SimulcastEngine simulcastEngine = channel.getTransformEngine().getSimulcastEngine();
        if (simulcastEngine != null
            && simulcastEngine.getSimulcastSenderManager().getOverrideOrder()
            != SimulcastSenderManager.SIMULCAST_LAYER_ORDER_NO_OVERRIDE)
        {
            simulcastEngine.getSimulcastSenderManager().setOverrideOrder(SimulcastSenderManager
                .SIMULCAST_LAYER_ORDER_NO_OVERRIDE);
            if (logger.isDebugEnabled())
            {
                int numEndpointsThatFitIn
                    = bitrateController.calcNumEndpointsThatFitIn();
                Endpoint self = channel.getEndpoint();
                Map<String, Object> map = new HashMap<>(2);
                map.put("self", self);
                map.put("numEndpointsThatFitIn", numEndpointsThatFitIn);
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("The uplink between the " +
                    "bridge and {self.id} can support " +
                    "{numEndpointsThatFitIn}. Allow " +
                    "streaming of high quality layers."));
            }

            return true;
        }

        return false;
    }

    public boolean decrease()
    {
        VideoChannel channel = bitrateController.getChannel();
        SimulcastEngine simulcastEngine = channel.getTransformEngine().getSimulcastEngine();
        if (simulcastEngine != null && simulcastEngine.getSimulcastSenderManager().getOverrideOrder() != SimulcastStream.SIMULCAST_LAYER_ORDER_BASE)
        {
            simulcastEngine.getSimulcastSenderManager().setOverrideOrder(
                SimulcastStream.SIMULCAST_LAYER_ORDER_BASE);

            if (logger.isDebugEnabled())
            {
                Endpoint self = channel.getEndpoint();
                int numEndpointsThatFitIn
                    = bitrateController.calcNumEndpointsThatFitIn();
                Map<String, Object> map = new HashMap<>(2);
                map.put("self", self);
                map.put("numEndpointsThatFitIn", numEndpointsThatFitIn);
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("The uplink between the " +
                    "bridge and {self.id} can only " +
                    "support {numEndpointsThatFitIn}. " +
                    "Make sure we only stream low " +
                    "quality layers."));
            }

            return true;
        }

        return false;
    }
}

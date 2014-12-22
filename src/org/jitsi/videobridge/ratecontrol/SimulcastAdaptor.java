/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.ratecontrol;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;
import org.jitsi.videobridge.utils.*;

import java.util.*;

/**
 * @author George Politis
 */
public class SimulcastAdaptor
        implements BitrateAdaptor
{
    private final BitrateController bitrateController;

    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastAdaptor</tt> class
     * and its instances to print debug information.
     */
    private static final org.jitsi.util.Logger logger
            = org.jitsi.util.Logger.getLogger(SimulcastAdaptor.class);

    public SimulcastAdaptor(BitrateController bitrateController)
    {
        this.bitrateController = bitrateController;
    }

    @Override
    public boolean touch()
    {
        return true;
    }

    @Override
    public boolean increase()
    {
        VideoChannel channel = bitrateController.getChannel();
        SimulcastManager mySM = channel.getSimulcastManager();
        if (mySM != null)
        {
            if (mySM.override(SimulcastManager
                    .SIMULCAST_LAYER_ORDER_NO_OVERRIDE))
            {
                if (logger.isDebugEnabled())
                {
                    int numEndpointsThatFitIn
                            = bitrateController.calcNumEndpointsThatFitIn();
                    Endpoint self = channel.getEndpoint();
                    Map<String, Object> map
                            = new HashMap<String, Object>(2);
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
        }

        return false;
    }

    @Override
    public boolean decrease()
    {
        VideoChannel channel = bitrateController.getChannel();
        SimulcastManager mySM = channel.getSimulcastManager();
        if (mySM != null)
        {
            if (mySM.override(
                    SimulcastManager.SIMULCAST_LAYER_ORDER_LQ))
            {

                if (logger.isDebugEnabled())
                {
                    Endpoint self = channel.getEndpoint();
                    int numEndpointsThatFitIn
                            = bitrateController.calcNumEndpointsThatFitIn();
                    Map<String, Object> map
                            = new HashMap<String, Object>(2);
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
        }

        return false;
    }
}

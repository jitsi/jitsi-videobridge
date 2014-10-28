/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim.messages;

import org.jitsi.videobridge.sim.*;
import org.json.simple.*;

import java.util.*;

/**
* Created by gp on 14/10/14.
*/
public class SimulcastMessagesMapper
{
    public String toJson(StartSimulcastLayerCommand command)
    {
        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"StartSimulcastLayerEvent\"");

        b.append(",\"simulcastLayer\":[");
        toJson(b, command.simulcastLayer);
        b.append("]}");

        return b.toString();
    }

    public String toJson(StopSimulcastLayerCommand command)
    {
        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"StopSimulcastLayerEvent\"");

        b.append(",\"simulcastLayer\":[");
        toJson(b, command.simulcastLayer);
        b.append("]}");

        return b.toString();
    }

    // NOTE(gp) custom JSON encoders/decoders are a maintenance burden and
    // a source of bugs. We should consider using a specialized library that
    // does that automatically, like Gson or Jackson. It would work like
    // this;
    //
    // Gson gson = new Gson();
    // String json = gson.toJson(event);
    //
    // So, basically it would work exactly like this custom encoder, but
    // without having to write a single line of code.

    public String toJson(SortedSet<SimulcastLayer> simulcastLayers)
    {
        StringBuilder b = new StringBuilder("[");
        for (SimulcastLayer simulcastLayer : simulcastLayers)
        {
            toJson(b, simulcastLayer);
        }
        b.append("]");

        return b.toString();
    }

    public String toJson(SimulcastLayersChangingEvent event)
    {
        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"SimulcastLayersChangingEvent\"");

        b.append(",\"endpointSimulcastLayers\":[");
        for (int i = 0; i < event.endpointSimulcastLayers.length; i++)
        {
            toJson(b, event.endpointSimulcastLayers[i]);
            if (i != event.endpointSimulcastLayers.length - 1)
                b.append(",");
        }
        b.append("]}");

        return b.toString();
    }

    public String toJson(SimulcastLayersChangedEvent event)
    {
        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"SimulcastLayersChangedEvent\"");

        b.append(",\"endpointSimulcastLayers\":[");
        for (int i = 0; i < event.endpointSimulcastLayers.length; i++)
        {
            toJson(b, event.endpointSimulcastLayers[i]);
            if (i != event.endpointSimulcastLayers.length - 1)
                b.append(",");
        }
        b.append("]}");

        return b.toString();
    }

    private void toJson(StringBuilder b,
                               EndpointSimulcastLayer endpointSimulcastLayer)
    {
        b.append("{\"endpoint\":");
        b.append(JSONValue.toJSONString(endpointSimulcastLayer.endpoint));
        b.append(",\"simulcastLayer\":");
        toJson(b, endpointSimulcastLayer.simulcastLayer);
        b.append("}");
    }

    public void toJson(StringBuilder b, SimulcastLayer simulcastLayer)
    {
        b.append("{\"primarySSRC\":");
        b.append(JSONValue.escape(
                Long.toString(simulcastLayer.getPrimarySSRC())));

        List<Long> associatedSSRCs = simulcastLayer.getAssociatedSSRCs();
        if (associatedSSRCs != null && associatedSSRCs.size() != 0)
        {
            b.append(",\"asociatedSSRCs\":[");
            for (int i = 0; i < associatedSSRCs.size(); i++)
            {
                b.append(JSONValue.escape(
                        Long.toString(associatedSSRCs.get(i))));

                if (i != associatedSSRCs.size() - 1)
                    b.append(",");
            }
            b.append("]");
        }
        b.append("}");
    }
}

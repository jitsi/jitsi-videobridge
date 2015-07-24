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
package org.jitsi.videobridge.simulcast.messages;

import org.jitsi.videobridge.simulcast.*;
import org.json.simple.*;

import java.util.*;

/**
* Created by gp on 14/10/14.
*/
public class SimulcastMessagesMapper
{
    public String toJson(StartSimulcastLayerCommand command)
    {
        if (command == null)
        {
            return "";
        }

        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"StartSimulcastLayerEvent\"");

        b.append(",\"simulcastLayer\":[");
        toJson(b, command.simulcastLayer);
        b.append("]}");

        return b.toString();
    }

    public String toJson(StopSimulcastLayerCommand command)
    {
        if (command == null)
        {
            return "";
        }

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
        if (simulcastLayers == null || simulcastLayers.isEmpty())
        {
            return "";
        }

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
        if (event == null)
        {
            return "";
        }

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

    public String toJson(NextSimulcastLayerStoppedEvent event)
    {
        if (event == null)
        {
            return "";
        }

        StringBuilder b = new StringBuilder(
            "{\"colibriClass\":\"NextSimulcastLayerStoppedEvent\"");

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
        if (event == null)
        {
            return "";
        }

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
        if (b == null || endpointSimulcastLayer == null)
        {
            return;
        }

        b.append("{\"endpoint\":");
        // NOTE(gp) do not change this to JSONValue.escape()! It breaks JSON
        // parsing at the client!
        b.append(JSONValue.toJSONString(endpointSimulcastLayer.endpoint));
        b.append(",\"simulcastLayer\":");
        toJson(b, endpointSimulcastLayer.simulcastLayer);
        b.append("}");
    }

    public void toJson(StringBuilder b, SimulcastLayer simulcastLayer)
    {
        if (b == null || simulcastLayer == null)
        {
            return;
        }

        b.append("{\"primarySSRC\":");
        b.append(Long.toString(simulcastLayer.getPrimarySSRC()));

        List<Long> associatedSSRCs = simulcastLayer.getAssociatedSSRCs();
        if (associatedSSRCs != null && associatedSSRCs.size() != 0)
        {
            b.append(",\"asociatedSSRCs\":[");
            for (int i = 0; i < associatedSSRCs.size(); i++)
            {
                b.append(Long.toString(associatedSSRCs.get(i)));

                if (i != associatedSSRCs.size() - 1)
                    b.append(",");
            }
            b.append("]");
        }
        b.append("}");
    }
}

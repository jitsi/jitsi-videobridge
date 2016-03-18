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
import org.jitsi.videobridge.simulcast.sendmodes.*;
import org.json.simple.*;

import java.util.*;

/**
 * Poor man's object mapper.
 *
 * @author George Politis
 */
public class SimulcastMessagesMapper
{
    public String toJson(StartSimulcastStreamCommand command)
    {
        if (command == null)
        {
            return "";
        }

        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"StartSimulcastStreamEvent\"");

        b.append(",\"simulcastStream\":[");
        toJson(b, command.simulcastStream);
        b.append("]}");

        return b.toString();
    }

    public String toJson(StopSimulcastStreamCommand command)
    {
        if (command == null)
        {
            return "";
        }

        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"StopSimulcastStreamEvent\"");

        b.append(",\"simulcastStream\":[");
        toJson(b, command.simulcastStream);
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

    public String toJson(SortedSet<SimulcastStream> simulcastStreams)
    {
        if (simulcastStreams == null || simulcastStreams.isEmpty())
        {
            return "";
        }

        StringBuilder b = new StringBuilder("[");
        for (SimulcastStream simulcastStream : simulcastStreams)
        {
            toJson(b, simulcastStream);
        }
        b.append("]");

        return b.toString();
    }

    public String toJson(SimulcastStreamsChangingEvent event)
    {
        if (event == null)
        {
            return "";
        }

        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"SimulcastStreamsChangingEvent\"");

        b.append(",\"endpointSimulcastStreams\":[");
        for (int i = 0; i < event.endpointSimulcastStreams.length; i++)
        {
            toJson(b, event.endpointSimulcastStreams[i]);
            if (i != event.endpointSimulcastStreams.length - 1)
                b.append(",");
        }
        b.append("]}");

        return b.toString();
    }

    public String toJson(NextSimulcastStreamStoppedEvent event)
    {
        if (event == null)
        {
            return "";
        }

        StringBuilder b = new StringBuilder(
            "{\"colibriClass\":\"NextSimulcastStreamStoppedEvent\"");

        b.append(",\"endpointSimulcastStreams\":[");
        for (int i = 0; i < event.endpointSimulcastStreams.length; i++)
        {
            toJson(b, event.endpointSimulcastStreams[i]);
            if (i != event.endpointSimulcastStreams.length - 1)
                b.append(",");
        }
        b.append("]}");

        return b.toString();
    }

    public String toJson(SimulcastStreamsChangedEvent event)
    {
        if (event == null)
        {
            return "";
        }

        StringBuilder b = new StringBuilder(
                "{\"colibriClass\":\"SimulcastStreamsChangedEvent\"");

        b.append(",\"endpointSimulcastStreams\":[");
        for (int i = 0; i < event.endpointSimulcastStreams.length; i++)
        {
            toJson(b, event.endpointSimulcastStreams[i]);
            if (i != event.endpointSimulcastStreams.length - 1)
                b.append(",");
        }
        b.append("]}");

        return b.toString();
    }

    private void toJson(StringBuilder b,
                               EndpointSimulcastStream endpointSimulcastStream)
    {
        if (b == null || endpointSimulcastStream == null)
        {
            return;
        }

        b.append("{\"endpoint\":");
        // NOTE(gp) do not change this to JSONValue.escape()! It breaks JSON
        // parsing at the client!
        b.append(JSONValue.toJSONString(endpointSimulcastStream.endpoint));
        b.append(",\"simulcastStream\":");
        toJson(b, endpointSimulcastStream.simulcastStream);
        b.append("}");
    }

    public void toJson(StringBuilder b, SimulcastStream simulcastStream)
    {
        if (b == null || simulcastStream == null)
        {
            return;
        }

        b.append("{\"primarySSRC\":");
        b.append(Long.toString(simulcastStream.getPrimarySSRC()));

        b.append(",\"rtxSSRC\":");
        b.append(Long.toString(simulcastStream.getRTXSSRC()));

        b.append(",\"isStreaming\":");
        b.append(Boolean.toString(simulcastStream.isStreaming()));
        b.append("}");
    }
}

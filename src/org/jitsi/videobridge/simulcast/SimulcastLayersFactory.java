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
package org.jitsi.videobridge.simulcast;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import java.util.*;

/**
 * Created by gp on 14/10/14.
 */
public class SimulcastLayersFactory
{
    /**
     * Updates the receiving simulcast layers of this <tt>Simulcast</tt>
     * instance.
     */
    public static SortedSet<SimulcastLayer> fromSourceGroups(
            List<SourceGroupPacketExtension> sourceGroups,
            SimulcastManager manager)
    {
        if (sourceGroups == null
                || sourceGroups.size() == 0)
            return null;

        Map<Long, SimulcastLayer> reverseMap
                = new HashMap<Long, SimulcastLayer>();

        // Build the simulcast layers.
        SortedSet<SimulcastLayer> layers = new TreeSet<SimulcastLayer>();
        for (SourceGroupPacketExtension sourceGroup : sourceGroups)
        {
            List<SourcePacketExtension> sources = sourceGroup.getSources();

            if (sources == null || sources.size() == 0
                    || !"SIM".equals(sourceGroup.getSemantics()))
            {
                continue;
            }

            // sources are in low to high order.
            int order = 0;
            for (SourcePacketExtension source : sources)
            {
                Long primarySSRC = source.getSSRC();
                SimulcastLayer simulcastLayer = new SimulcastLayer(manager,
                        primarySSRC, order++);

                // Add the layer to the reverse map.
                reverseMap.put(primarySSRC, simulcastLayer);

                // Add the layer to the sorted set.
                layers.add(simulcastLayer);
            }

        }

        // Append associated SSRCs from other source groups.
        for (SourceGroupPacketExtension sourceGroup : sourceGroups)
        {
            List<SourcePacketExtension> sources = sourceGroup.getSources();

            if (sources == null || sources.size() == 0
                    || "SIM".equals(sourceGroup.getSemantics()))
            {
                continue;
            }

            SimulcastLayer simulcastLayer = null;

            // Find all the associated ssrcs for this group.
            Set<Long> ssrcs = new HashSet<Long>();
            for (SourcePacketExtension source : sources)
            {
                Long ssrc = source.getSSRC();
                ssrcs.add(source.getSSRC());
                if (reverseMap.containsKey(ssrc))
                {
                    simulcastLayer = reverseMap.get(ssrc);
                }
            }

            if (simulcastLayer != null)
            {
                simulcastLayer.associateSSRCs(ssrcs);
            }
        }

        return layers;
    }
}

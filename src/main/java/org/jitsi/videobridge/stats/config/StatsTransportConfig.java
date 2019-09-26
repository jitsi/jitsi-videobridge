/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.stats.config;

import org.jitsi.videobridge.stats.*;

import java.time.*;

public class StatsTransportConfig
{
    /**
     * The name of the stats transport.  This should
     * match one of the strings in {@link StatsManagerBundleActivator}
     * TODO: change to an enum
     */
    protected String name;
    protected Duration interval;

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Duration getInterval()
    {
        return interval;
    }

    public void setInterval(Duration interval)
    {
        this.interval = interval;
    }
}

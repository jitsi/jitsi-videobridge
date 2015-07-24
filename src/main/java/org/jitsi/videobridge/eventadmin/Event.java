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
package org.jitsi.videobridge.eventadmin;

import java.util.*;

/**
 * An event. <tt>Event</tt> objects are delivered to EventHandler services which
 * subscribe to the topic of the event.
 *
 * @author George Politis
 */
public class Event
{
    private final String topic;
    private final Dictionary properties;

    public Event(String topic, Dictionary properties)
    {
        this.topic = topic;
        this.properties = properties;
    }

    public Object getProperty(Object key)
    {
        return this.properties != null ? properties.get(key) : null;
    }

    public String getTopic()
    {
        return topic;
    }
}

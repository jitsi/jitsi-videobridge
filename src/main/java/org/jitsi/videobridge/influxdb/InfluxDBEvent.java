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
package org.jitsi.videobridge.influxdb;

/**
 * Represents an event to be logged. An event has a name and a list of
 * (key, value) pairs. The pairs are represented with the two arrays
 * {@link #columns} and {@link #values} (the keys are though of as the names of
 * the columns of a table).
 *
 * @author Boris Grozev
 */
public class InfluxDBEvent
{
    /**
     * The name of this <tt>Event</tt>.
     */
    private final String name;

    /**
     * The column names/keys of this <tt>Event</tt>.
     */
    private final String[] columns;

    /**
     * The values corresponding to the elements of {@link #columns}.
     */
    private final Object[] values;

    /**
     * Whether the local time should be used when this <tt>Event</tt> is being
     * logged.
     */
    private boolean useLocalTime = true;

    /**
     * Initializes a new <tt>Event</tt>. The number of elements in
     * <tt>columns</tt> and <tt>values</tt> MUST match.
     *
     * @param name the name of the event.
     * @param columns the column names/keys. The number of elements MUST be the
     * same as the same number of elements in <tt>values</tt>.
     * @param values the values. The number of elements MUST be the same as the
     * same number of elements in <tt>columns</tt>.
     */
    public InfluxDBEvent(String name, String[] columns, Object[] values)
    {
        this.name = name;
        this.columns = columns;
        this.values = values;
    }

    /**
     * Gets the name of this <tt>Event</tt>.
     * @return the name of this <tt>Event</tt>.
     */
    public String getName()
    {
        return name;
    }

    /**
     * Gets the column names/keys of this <tt>Event</tt>.
     * @return the column names/keys of this <tt>Event</tt>.
     */
    public String[] getColumns()
    {
        return columns;
    }

    /**
     * Gets the values corresponding to the column names/keys of this
     * <tt>Event</tt>.
     * @return the values corresponding to the column names/keys of this
     * <tt>Event</tt>.
     */
    public Object[] getValues()
    {
        return values;
    }

    /**
     * Sets the <tt>useLocalTime</tt> flag of this <tt>Event</tt>.
     * @param useLocalTime the value to set.
     */
    public void setUseLocalTime(boolean useLocalTime)
    {
        this.useLocalTime = useLocalTime;
    }

    /**
     * Gets the <tt>useLocalTime</tt> flag of this <tt>Event</tt>.
     * @return the <tt>useLocalTime</tt> flag of this <tt>Event</tt>.
     */
    public boolean useLocalTime()
    {
        return useLocalTime;
    }

}

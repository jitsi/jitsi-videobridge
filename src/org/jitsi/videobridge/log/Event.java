/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.log;

/**
 * Represents an event to be logged. An event has a name and a list of
 * (key, value) pairs. The pairs are represented with the two arrays
 * {@link #columns} and {@link #values} (the keys are though of as the names of
 * the columns of a table).
 *
 * @author Boris Grozev
 */
public class Event
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
    public Event(String name, String[] columns, Object[] values)
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

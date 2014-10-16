/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.log;

/**
 * A utility class with static methods which initialize <tt>Event</tt> instances
 * with pre-determined fields.
 *
 * @author Boris Grozev
 */
public class EventFactory
{
    /**
     * The names of the columns of a "conference created" event.
     */
    private static final String[] CONFERENCE_CREATED_COLUMNS
        = new String[] {"id"};

    /**
     * The names of the columns of a "content created" event.
     */
    private static final String[] CONTENT_CREATED_COLUMNS
        = new String[] {"name", "conference_id"};

    /**
     * The names of the columns of a "channel created" event.
     */
    private static final String[] CHANNEL_CREATED_COLUMNS
        = new String[] {"id", "content_name", "conference_id"};

    /**
     * Creates a new "conference created" <tt>Event</tt>.
     * @param id the ID of the conference.
     * @return the <tt>Event</tt> which was created.
     */
    public static Event conferenceCreated(String id)
    {
        return new Event("conference_created",
                         CONFERENCE_CREATED_COLUMNS,
                         new Object[] {id});
    }

    /**
     * Creates a new "content created" <tt>Event</tt>.
     * @param name the name of the content.
     * @param conferenceId the ID of the content's parent conference.
     * @return the <tt>Event</tt> which was created.
     */
    public static Event contentCreated(String name, String conferenceId)
    {
        return new Event("content_created",
                         CONTENT_CREATED_COLUMNS,
                         new Object[] {name, conferenceId});
    }

    /**
     * Creates a new "channel created" <tt>Event</tt>.
     * @param id the ID of the channel.
     * @param contentName the name of the channel's parent content.
     * @param conferenceId the ID of the channel's parent conference.
     * @return the <tt>Event</tt> which was created.
     */
    public static Event channelCreated(
            String id,
            String contentName,
            String conferenceId)
    {
        return new Event("channel_created",
                         CHANNEL_CREATED_COLUMNS,
                         new Object[] {id, contentName, conferenceId});
    }

    /**
     * Creates a new "conference created" <tt>Event</tt>.
     * @param id the ID of the conference.
     * @return the <tt>Event</tt> which was created.
     */
    public static Event conferenceExpired(String id)
    {
        return new Event("conference_expired",
                         CONFERENCE_CREATED_COLUMNS /* reuse */,
                         new Object[] {id});
    }

    /**
     * Creates a new "content created" <tt>Event</tt>.
     * @param name the name of the content.
     * @param conferenceId the ID of the content's parent conference.
     * @return the <tt>Event</tt> which was created.
     */
    public static Event contentExpired(String name, String conferenceId)
    {
        return new Event("content_expired",
                         CONTENT_CREATED_COLUMNS /* reuse */,
                         new Object[] {name, conferenceId});
    }

    /**
     * Creates a new "channel created" <tt>Event</tt>.
     * @param id the ID of the channel.
     * @param contentName the name of the channel's parent content.
     * @param conferenceId the ID of the channel's parent conference.
     * @return the <tt>Event</tt> which was created.
     */
    public static Event channelExpired(
            String id,
            String contentName,
            String conferenceId)
    {
        return new Event("channel_expired",
                         CHANNEL_CREATED_COLUMNS /* reuse */,
                         new Object[] {id, contentName, conferenceId});
    }
}

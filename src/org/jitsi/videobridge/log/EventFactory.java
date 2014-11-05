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
        = new String[] {"conference_id"};

    /**
     * The names of the columns of a "conference expired" event.
     */
    private static final String[] CONFERENCE_EXPIRED_COLUMNS
        = CONFERENCE_CREATED_COLUMNS;

    /**
     * The names of the columns of a "content created" event.
     */
    private static final String[] CONTENT_CREATED_COLUMNS
        = new String[] {"name", "conference_id"};

    /**
     * The names of the columns of a "content expired" event.
     */
    private static final String[] CONTENT_EXPIRED_COLUMNS
            = CONTENT_CREATED_COLUMNS;

    /**
     * The names of the columns of a "channel created" event.
     */
    private static final String[] CHANNEL_CREATED_COLUMNS
        = new String[] {"channel_id", "content_name", "conference_id"};

    /**
     * The names of the columns of a "channel expired" event.
     */
    private static final String[] CHANNEL_EXPIRED_COLUMNS
        = CHANNEL_CREATED_COLUMNS;

    /**
     * The names of the columns of a "transport created" event.
     */
    private static final String[] TRANSPORT_CREATED_COLUMNS
        = new String[]
            {
                    "hash_code",
                    "conference_id",
                    "num_components",
                    "ufrag",
                    "is_controlling"
            };

    /**
     * The names of the columns of a "transport manager channel added" event.
     */
    private static final String[] TRANSPORT_CHANNEL_ADDED_COLUMNS
        = new String[]
            {
                    "hash_code",
                    "conference_id",
                    "channel_id",
            };

    /**
     * The names of the columns of a "transport manager channel removed" event.
     */
    private static final String[] TRANSPORT_CHANNEL_REMOVED_COLUMNS
        = TRANSPORT_CHANNEL_ADDED_COLUMNS;

    /**
     * The names of the columns of a "transport manager connected" event.
     */
    private static final String[] TRANSPORT_CONNECTED_COLUMNS
        = new String[]
            {
                    "hash_code",
                    "conference_id",
                    "selected_pairs"
            };

    /**
     * The names of the columns of a "transport manager connected" event.
     */
    private static final String[] TRANSPORT_STATE_CHANGED_COLUMNS
        = new String[]
            {
                    "hash_code",
                    "conference_id",
                    "old_state",
                    "new_state"
            };

    /**
     * Creates a new "conference created" <tt>Event</tt>.
     * @param id the ID of the conference.
     *
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
     *
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
     *
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
     * Creates a new "conference expired" <tt>Event</tt>.
     * @param id the ID of the conference.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event conferenceExpired(String id)
    {
        return new Event("conference_expired",
                         CONFERENCE_EXPIRED_COLUMNS,
                         new Object[] {id});
    }

    /**
     * Creates a new "content expired" <tt>Event</tt>.
     * @param name the name of the content.
     * @param conferenceId the ID of the content's parent conference.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event contentExpired(String name, String conferenceId)
    {
        return new Event("content_expired",
                         CONTENT_EXPIRED_COLUMNS,
                         new Object[] {name, conferenceId});
    }

    /**
     * Creates a new "channel expired" <tt>Event</tt>.
     * @param id the ID of the channel.
     * @param contentName the name of the channel's parent content.
     * @param conferenceId the ID of the channel's parent conference.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event channelExpired(
            String id,
            String contentName,
            String conferenceId)
    {
        return new Event("channel_expired",
                         CHANNEL_EXPIRED_COLUMNS,
                         new Object[] {id, contentName, conferenceId});
    }

    /**
     * Creates a new "transport created" <tt>Event</tt>.
     * @param hashCode the hash code of the transport manager object.
     * @param conferenceId the ID of the transport manager's parent conference.
     * @param numComponents the number of ICE components.
     * @param ufrag the local ufrag.
     * @param isControlling whether the ICE agent has the controlling or
     * controlled role.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportCreated(
            int hashCode,
            String conferenceId,
            int numComponents,
            String ufrag,
            boolean isControlling)
    {
        return new Event("transport_created",
                         TRANSPORT_CREATED_COLUMNS,
                         new Object[]{
                                 String.valueOf(hashCode),
                                 conferenceId,
                                 numComponents,
                                 ufrag,
                                 Boolean.valueOf(isControlling).toString()});
    }

    /**
     * Creates a new "transport channel added" <tt>Event</tt>.
     * @param hashCode the hash code of the transport manager object.
     * @param conferenceId the ID of the transport manager's parent conference.
     * @param channelId the ID of the channel which was added.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportChannelAdded(
            int hashCode,
            String conferenceId,
            String channelId)
    {
        return new Event("transport_channel_added",
                         TRANSPORT_CHANNEL_ADDED_COLUMNS,
                         new Object[]{
                                 String.valueOf(hashCode),
                                 conferenceId,
                                 channelId});
    }

    /**
     * Creates a new "transport channel removed" <tt>Event</tt>.
     * @param hashCode the hash code of the transport manager object.
     * @param conferenceId the ID of the transport manager's parent conference.
     * @param channelId the ID of the channel which was added.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportChannelRemoved(
            int hashCode,
            String conferenceId,
            String channelId)
    {
        return new Event("transport_channel_removed",
                         TRANSPORT_CHANNEL_REMOVED_COLUMNS,
                         new Object[]{
                                 String.valueOf(hashCode),
                                 conferenceId,
                                 channelId});
    }

    /**
     * Creates a new "transport connected" <tt>Event</tt>.
     * @param hashCode the hash code of the transport manager object
     * @param conferenceId the ID of the transport manager's parent conference.
     * @param selectedPairs a <tt>String</tt> representation of the ICE pairs
     * which were selected for each ICE component.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportConnected(
            int hashCode,
            String conferenceId,
            String selectedPairs)
    {
        return new Event("transport_connected",
                         TRANSPORT_CONNECTED_COLUMNS,
                         new Object[]{
                                 String.valueOf(hashCode),
                                 conferenceId,
                                 selectedPairs});
    }

    /**
     * Creates a new "transport manager state changed" <tt>Event</tt>.
     * @param hashCode the hash code of the transport manager object
     * @param conferenceId the ID of the transport manager's parent conference.
     * @param oldState the old ICE state.
     * @param newState the new ICE state.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event transportStateChanged(
            int hashCode,
            String conferenceId,
            String oldState,
            String newState)
    {
        return new Event("transport_state_changed",
                         TRANSPORT_STATE_CHANGED_COLUMNS,
                         new Object[]{
                                 String.valueOf(hashCode),
                                 conferenceId,
                                 oldState,
                                 newState});
    }
}

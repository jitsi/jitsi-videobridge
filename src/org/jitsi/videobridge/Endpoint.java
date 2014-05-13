/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import net.java.sip.communicator.util.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;

/**
 * Represents an endpoint of a participant in a <tt>Conference</tt>.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 */
public class Endpoint
{
    /**
     * The list of <tt>Channel</tt>s associated with this <tt>Endpoint</tt>.
     */
    private final List<WeakReference<RtpChannel>> channels
        = new LinkedList<WeakReference<RtpChannel>>();

    /**
     * SCTP connection bound to this endpoint.
     */
    private WeakReference<SctpConnection> sctpConnection
        = new WeakReference<SctpConnection>(null);

    /**
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * Initializes a new <tt>Endpoint</tt> instance with a specific (unique)
     * identifier/ID of the endpoint of a participant in a <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt> with which the new instance is to be initialized
     */
    public Endpoint(String id)
    {
        if (id == null)
            throw new NullPointerException("id");

        this.id = id;
    }

    /**
     * Adds a specific <tt>Channel</tt> to the list of <tt>Channel</tt>s
     * associated with this <tt>Endpoint</tt>.
     *
     * @param channel the <tt>Channel</tt> to add to the list of
     * <tt>Channel</tt>s associated with this <tt>Endpoint</tt>
     * @return <tt>true</tt> if the list of <tt>Channel</tt>s associated with
     * this <tt>Endpoint</tt> changed as a result of the method invocation;
     * otherwise, <tt>false</tt>
     */
    public boolean addChannel(RtpChannel channel)
    {
        if (channel == null)
            throw new NullPointerException("channel");

        synchronized (channels)
        {
            for (Iterator<WeakReference<RtpChannel>> i = channels.iterator();
                    i.hasNext();)
            {
                RtpChannel c = i.next().get();

                if (c == null)
                    i.remove();
                else if (c.equals(channel))
                    return false;
            }

            return channels.add(new WeakReference<RtpChannel>(channel));
        }
    }

    /**
     * Gets the (unique) identifier/ID of this instance.
     *
     * @return the (unique) identifier/ID of this instance
     */
    public final String getID()
    {
        return id;
    }

    /**
     * Removes a specific <tt>Channel</tt> from the list of <tt>Channel</tt>s
     * associated with this <tt>Endpoint</tt>.
     *
     * @param channel the <tt>Channel</tt> to remove from the list of
     * <tt>Channel</tt>s associated with this <tt>Endpoint</tt>
     * @return <tt>true</tt> if the list of <tt>Channel</tt>s associated with
     * this <tt>Endpoint</tt> changed as a result of the method invocation;
     * otherwise, <tt>false</tt>
     */
    public boolean removeChannel(RtpChannel channel)
    {
        if (channel == null)
            return false;

        synchronized (channels)
        {
            for (Iterator<WeakReference<RtpChannel>> i = channels.iterator();
                    i.hasNext();)
            {
                Channel c = i.next().get();

                if (c == null)
                {
                    i.remove();
                }
                else if (c.equals(channel))
                {
                    i.remove();
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Sets the <tt>SctpConnection</tt> associated with this <tt>Endpoint</tt>.
     * @param sctpConnection the <tt>SctpConnection</tt> that will be bound to
     *                       this <tt>Endpoint</tt>.
     */
    public void setSctpConnection(SctpConnection sctpConnection)
    {
        this.sctpConnection
            = new WeakReference<SctpConnection>(sctpConnection);

        // FIXME: remove
        //if(sctpConnection != null)
        //{
        //    new WebRTCDataChannelSample(sctpConnection);
        //}
    }

    /**
     * Returns an <tt>SctpConnection</tt> bound to this <tt>Endpoint</tt>.
     * @return an <tt>SctpConnection</tt> bound to this <tt>Endpoint</tt>
     *         or <tt>null</tt> otherwise.
     */
    public SctpConnection getSctpConnection()
    {
        return this.sctpConnection.get();
    }

    /**
     * Sample usage of SctpConnection for sending/receiving the data.
     * FIXME: remove
     *
     * @author Pawel Domas
     */
    class WebRTCDataChannelSample
        implements SctpConnection.WebRtcDataStreamListener,
                   WebRtcDataStream.DataCallback
    {
        private final Logger logger
            = Logger.getLogger(WebRTCDataChannelSample.class);

        private final SctpConnection sctp;

        WebRTCDataChannelSample(SctpConnection sctpConnection)
        {
            this.sctp = sctpConnection;
            sctpConnection.addChannelListener(this);
        }

        @Override
        public void onSctpConnectionReady()
        {
            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        // onChannelOpened will be triggered for this channel
                        sctp.openChannel(0, 0, 0, 1, "BridgeTo" + getID());
                    }
                    catch (Exception e)
                    {
                        logger.error("Error allocating new WebRTC data channel" +
                                         " to " + getID(), e);
                    }
                }
            }).start();
        }

        @Override
        public void onChannelOpened(WebRtcDataStream newStream)
        {
            newStream.setDataCallback(this);

            sendHello(newStream);
        }

        private void sendHello(WebRtcDataStream dataStream)
        {
            try
            {
                logger.info("!!! " + getID() + " sending hello SID: "
                                + dataStream.getSid());

                dataStream.sendString("Hello "+getID());
            }
            catch (IOException e)
            {
                logger.error("Error sending text to " + getID(), e);
            }
        }

        @Override
        public void onStringData(WebRtcDataStream src, String msg)
        {
            logger.error("!!! " + getID() + " SENT US A STRING"
                             + " ON STREAM: " + src.getLabel()
                             + " RUNS ON SID: " + src.getSid()
                             + " CONTENT: " + msg);
        }

        @Override
        public void onBinaryData(WebRtcDataStream src, byte[] data)
        {
            logger.error("!!! " + getID() + " SENT US BIN DATA "
                             + " ON STREAM: " + src.getLabel()
                             + " RUNS ON SID: " + src.getSid()
                             + " CONTENT LEN: " + data.length);
        }
    }
}

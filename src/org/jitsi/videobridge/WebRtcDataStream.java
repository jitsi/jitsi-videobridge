/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import net.java.sip.communicator.util.*;
import org.jitsi.sctp4j.*;

import java.io.*;

/**
 * Class represent WebRTC data channel that runs on top of DTLS/SCTP connection.
 *
 * @author Pawel Domas
 */
public class WebRtcDataStream
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(WebRtcDataStream.class);

    /**
     * <tt>SctpSocket</tt> used for sending SCTP data.
     */
    private final SctpSocket socket;

    /**
     * SCTP stream id used by this stream.
     */
    private final int sid;

    /**
     * Name of this stream.
     */
    private final String label;

    /**
     * Indicates whether this channel has been acknowledged by remote peer.
     */
    private boolean acknowledged;

    /**
     * Initializes new instance of <tt>WebRtcDataStream</tt> with specified
     * parameters.
     * @param socket the SCTP socket used for transport.
     * @param sid SCTP stream id to be used by this channel.
     * @param label name of the channel.
     * @param acknowledged indicates if this channel has been already
     *                     acknowledged by remote peer.
     */
    WebRtcDataStream(SctpSocket socket, int sid, String label,
                     boolean acknowledged)
    {
        this.socket = socket;
        this.sid = sid;
        this.label = label;
        this.acknowledged = acknowledged;
    }

    /**
     * Returns the name of this WebRTC data stream.
     * @return the name of this WebRTC data stream.
     */
    public String getLabel()
    {
        return label;
    }

    /**
     * Indicates whether this stream has been acknowledged by remote peer.
     * @return <tt>true</tt> if this stream has been acknowledged by remote peer
     */
    public boolean isAcknowledged()
    {
        return this.acknowledged;
    }

    protected void ackReceived()
    {
        this.acknowledged = true;

        logger.trace("Channel on sid: " + sid + " is now acknowledged");
    }

    /**
     * Fired when string UTF8 encoded data is received on this stream.
     * @param stringMsg UTF8 encoded string data received.
     */
    public void onStringMsg(String stringMsg)
    {
        // FIXME: replace with callback/listener or stream interface
        logger.info("!!! SCTP STRING: " + stringMsg);
    }

    /**
     * Sends given text message over this WebRTC data channel using UTF8
     * encoding.
     * @param strMsg the text to be sent.
     * @throws IOException if IO error occurs while sending the message.
     */
    public void sendString(String strMsg)
        throws IOException
    {
        try
        {
            byte[] raw = strMsg.getBytes("UTF8");

            int res
                = socket.send(
                    raw, true, sid,
                    SctpConnection.WEB_RTC_PPID_STRING);

            if(res != raw.length)
            {
                throw new IOException("Failed to send the data");
            }
        }
        catch (UnsupportedEncodingException e)
        {
            // Should not happen
            throw new IOException(e);
        }
    }

    /**
     * Fired when binary data is received on this WebRTC data channel.
     * @param binMsg the buffer that holds binary message received.
     */
    public void onBinaryMsg(byte[] binMsg)
    {
        // FIXME: replace with callback/listener or stream interface
        logger.info("!!! SCTP BIN: " + binMsg.length);
    }

    /**
     * Sends binary data over this WebRTC data stream.
     * @param bytes the buffer that contains the data to be sent.
     * @throws IOException if IO error occurs while sending the data.
     */
    public void sendBinary(byte[] bytes)
        throws IOException
    {
        int res = socket.send(bytes, true, sid,
                    SctpConnection.WEB_RTC_PPID_BIN);
        if(res != bytes.length)
        {
            throw new IOException("Failed to send the data");
        }
    }
}

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
     * The parent {@link SctpConnection} instance.
     */
    private final SctpConnection sctpConnection;

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
     * Data callback that will be fired whenever data is received.
     */
    private DataCallback dataCallback;

    /**
     * Initializes new instance of <tt>WebRtcDataStream</tt> with specified
     * parameters.
     * @param connection the parent {@link SctpConnection} instance.
     * @param socket the SCTP socket used for transport.
     * @param sid SCTP stream id to be used by this channel.
     * @param label name of the channel.
     * @param acknowledged indicates if this channel has been already
     *                     acknowledged by remote peer.
     */
    WebRtcDataStream(SctpConnection connection,
                     SctpSocket socket, int sid, String label,
                     boolean acknowledged)
    {
        this.sctpConnection = connection;
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
     * Returns the parent {@link SctpConnection} which owns this instance.
     * @return {@link SctpConnection}
     */
    public SctpConnection getSctpConnection()
    {
        return sctpConnection;
    }

    /**
     * Returns SCTP stream id on which this <tt>WebRtcDataStream</tt> is
     * running.
     * @return SCTP stream id on which this <tt>WebRtcDataStream</tt> is
     *         running.
     */
    public int getSid()
    {
        return sid;
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
        if(dataCallback != null)
        {
            dataCallback.onStringData(this, stringMsg);
        }
        else
        {
            // NOTE consider adding analytics for the missed out data to detect
            // bugs ?
            logger.error(
                String.format(
                    "Unprocessed data on %s (SID=%d) - no callback registered",
                    sctpConnection.getLoggingId(),
                    sid));
        }
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
            byte[] bytes = strMsg.getBytes("UTF-8");
            int res
                = socket.send(
                        bytes,
                        true,
                        sid,
                        SctpConnection.WEB_RTC_PPID_STRING);

            if(res != bytes.length)
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
        if(dataCallback != null)
        {
            dataCallback.onBinaryData(this, binMsg);
        }
        else
        {
            // NOTE consider adding analytics for the missed out data to detect
            // bugs ?
            logger.error(
                String.format(
                    "Unprocessed data on %s (SID=%d) - no callback registered",
                    sctpConnection.getLoggingId(),
                    sid));
        }
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

    /**
     * Sets the callback that will be fired whenever string or binary message is
     * received on this <tt>WebRtcDataStream</tt>.
     *
     * @param dataCallback the callback that will be fired when any message is
     *                     received on this <tt>WebRtcDataStream</tt>.
     */
    public void setDataCallback(DataCallback dataCallback)
    {
        this.dataCallback = dataCallback;
    }

    /**
     * Returns the callback that will be fired whenever string or binary message is
     * received on this <tt>WebRtcDataStream</tt>.
     */
    public DataCallback getDataCallback()
    {
        return dataCallback;
    }


    /**
     * Interface used to receive data on this stream.
     * It is message oriented and supports text or binary payload.
     */
    public interface DataCallback
    {
        /**
         * Fired when <tt>String</tt> message is received on this
         * <tt>WebRtcDataStream</tt>.
         *
         * @param src the <tt>WebRtcDataStream</tt> on which this message was
         *            received.
         * @param msg <tt>String</tt> message content.
         */
        default void onStringData(WebRtcDataStream src, String msg)
        {
        }

        /**
         * Fired when binary message is received on this
         * <tt>WebRtcDataStream</tt>.
         *
         * @param src the <tt>WebRtcDataStream</tt> on which this message was
         *            received.
         * @param data <tt>byte</tt> buffer that contains message content.
         */
        default void onBinaryData(WebRtcDataStream src, byte[] data)
        {
        }
    }
}

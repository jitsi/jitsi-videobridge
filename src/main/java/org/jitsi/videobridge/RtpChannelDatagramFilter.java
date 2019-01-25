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

import java.net.*;

import org.ice4j.socket.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * Filters RTP or RTCP packet for a specific <tt>RtpChannel</tt>.
 *
 * @author Boris Grozev
 */
public class RtpChannelDatagramFilter
    implements DatagramPacketFilter
{
    /**
     * The <tt>Logger</tt> used by the <tt>RtpChannelDatagramFilter</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RtpChannelDatagramFilter.class);

     /**
     * Whether to accept non-RTP/RTCP packets (e.g. DTLS, STUN, ZRTP).
     */
    private boolean acceptNonRtp = false;

    /**
     * The <tt>RtpChannel</tt> for which this <tt>RtpChannelDatagramFilter</tt>
     * works.
     */
    private final RtpChannel channel;

    /**
     * Whether we have logged a warning about missing payload-type numbers.
     */
    private boolean missingPtsWarningLogged = false;

    /**
     * Whether this <tt>DatagramFilter</tt> is to accept RTP packets (if
     * <tt>false</tt>) or RTCP packets (if <tt>true</tt>).
     */
    private final boolean rtcp;

    /**
     * Initializes an <tt>RtpChannelDatagramFilter</tt>.
     *
     * @param channel the channel for which to work.
     * @param rtcp whether to accept RTP or RTCP packets.
     */
    protected RtpChannelDatagramFilter(RtpChannel channel, boolean rtcp)
    {
        this(channel, rtcp, false);
    }

    /**
     * Initializes a new {@code RtpChannelDatagramFilter} instance.
     *
     * @param channel the channel for which to work.
     * @param rtcp whether to accept RTP or RTCP packets.
     * @param acceptNonRtp {@code true} to accept packets which are neither RTP
     * nor RTCP (e.g. DTLS, STUN, ZRTP); otherwise, {@code false}.
     */
    RtpChannelDatagramFilter(RtpChannel channel,
                             boolean rtcp,
                             boolean acceptNonRtp)
    {
        this.channel = channel;
        this.rtcp = rtcp;
        this.acceptNonRtp = acceptNonRtp;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean accept(DatagramPacket p)
    {
        byte[] buf = p.getData();
        int off = p.getOffset();
        int len = p.getLength();

        // If isRtpRtcp fails, this is not a valid RTP packet either.
        if (!RawPacket.isRtpRtcp(buf, off, len))
        {
            return acceptNonRtp && DTLSDatagramFilter.isDTLS(p);
        }

        if (RTCPUtils.isRtcp(buf, off, len))
        {
            return rtcp && acceptRTCP(buf, off, len);
        }
        else
        {
            return !rtcp && acceptRTP(RawPacket.getPayloadType(buf, off, len));
        }
    }

    /**
     * Returns <tt>true</tt> if this <tt>RtpChannelDatagramFilter</tt> should
     * accept an RTCP packet described by <tt>data</tt>, <tt>off</tt>, and
     * <tt>len</tt>. Checks whether the SSRC of the packet sender is an SSRC
     * received on the <tt>channel</tt>.
     *
     * @param data
     * @param len
     * @param off
     * @return
     */
    private boolean acceptRTCP(byte[] data, int off, int len)
    {
        if (len >= 8)
        {
            int packetSenderSSRC = RTPUtils.readInt(data, off + 4);

            int[] channelSSRCs =  channel.getDefaultReceiveSSRCs();

            for (int channelSSRC : channelSSRCs)
            {
                if (channelSSRC == packetSenderSSRC)
                {
                    return true;
                }
            }

            channelSSRCs = channel.getReceiveSSRCs();

            for (int channelSSRC : channelSSRCs)
            {
                if (channelSSRC == packetSenderSSRC)
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns <tt>true</tt> if this <tt>RtpChannelDatagramFilter</tt> should
     * accept an RTP packet with a payload type number <tt>pt</tt>. Checks
     * whether <tt>pt</tt> is a payload type configured for the
     * <tt>channel</tt>.
     *
     * @param pt the payload type number of the packet.
     * @return <tt>true</tt> if this <tt>RtpChannelDatagramFilter</tt> should
     * accept an RTP packet with a payload type number <tt>pt</tt>; otherwise,
     * <tt>false</tt>.
     */
    private boolean acceptRTP(int pt)
    {
        int[] channelPTs = channel.getReceivePTs();

        if (channelPTs == null || channelPTs.length == 0)
        {
            // The controller of the conference has not specified RTP
            // payload-type numbers for this channel.
            // Without bundle we can safely accept all packets, allowing the
            // bridge to operate without payload-type numbers being specified.
            // With bundle, however, we cannot operate without PT numbers for
            // each channel, because we use them as a base for demultiplexing.
            if (channel.getChannelBundleId() == null)
            {
                return true;
            }
            else
            {
                // This likely indicates a problem with the conference focus,
                // which might be hard to debug.
                if (!missingPtsWarningLogged)
                {
                    missingPtsWarningLogged = true;
                    logger.warn(
                            "No payload-types specified for channel "
                                + channel.getID()
                                + " while bundle is in use."
                                + " Packets are dropped.");
                }

                return false;
            }
        }

        for (int channelPT : channelPTs)
        {
            if (channelPT == pt)
                return true;
        }
        return false;
    }

    /**
     * Sets the flag which controls whether to accept non-RTP/RTCP packets.
     *
     * @param acceptNonRtp the value to set.
     */
    public void setAcceptNonRtp(boolean acceptNonRtp)
    {
        this.acceptNonRtp = acceptNonRtp;
    }
}

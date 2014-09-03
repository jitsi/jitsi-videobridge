/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.net.*;

import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.rtp.translator.*;

/**
 * Filters RTP or RTCP packet for a specific <tt>RtpChannel</tt>.
 */
class RtpChannelDatagramFilter
    implements DatagramPacketFilter
{

    /*
     * The <tt>DatagramPacketFilter</tt> which accepts DTLS
     * <tt>DatagramPacket</tt>s when {@link #acceptNonRtp} equals <tt>true</tt>.
     */
    private static final DatagramPacketFilter DTLS_DATAGRAM_FILTER
        = new DTLSDatagramFilter();
     /**
     * Whether to accept non-RTP and non-RTCP packets (DTLS, STUN, ZRTP)
     */
    private boolean acceptNonRtp = false;

    /**
     * The <tt>RtpChannel</tt> for which this <tt>RtpChannelDatagramFilter</tt>
     * works.
     */
    private final RtpChannel channel;

    /**
     * Whether or not to check the value of the RTCP Sender SSRC field against
     * the known SSRCs for {@link #channel}. If set to <tt>false</tt>, this
     * <tt>DatagramPacketFilter</tt> will accept all RTCP packets, regardless
     * of their Sender SSRC field.
     */
    private boolean checkRtcpSsrc = true;

    /**
     * Whether or not to check the value of RTP Payload-Type field against the
     * known payload types for {@link #channel}. If set to <tt>false</tt>, this
     * <tt>DatagramPacketFilter</tt> will accept all RTP packets, regardless
     * of their payload type number.
     */
    private boolean checkRtpPayloadType = true;

    /**
     * Whether this <tt>DatagramFilter</tt> is to accept RTP packets (if
     * <tt>false</tt>) or RTCP packets (if <tt>true</tt>).
     */
    private final boolean rtcp;

    /**
     * Initializes an <tt>RtpChannelDatagramFilter</tt>.
     * @param channel the channel for which to work.
     * @param rtcp whether to accept RTP or RTCP packets.
     */
    RtpChannelDatagramFilter(RtpChannel channel, boolean rtcp)
    {
        this(channel, rtcp, false);
    }

    /**
     * Initializes an <tt>RtpChannelDatagramFilter</tt>.
     * @param channel the channel for which to work.
     * @param rtcp whether to accept RTP or RTCP packets.
     * @param acceptNonRtp whether to accept packets which are neither RTP
     * nor RTCP (e.g. DTLS, STUN, ZRTP).
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
        int len = p.getLength();

        if (len >= 4)
        {
            byte[] data = p.getData();
            int off = p.getOffset();

            if (((data[off + 0] & 0xc0) >> 6) == 2) //RTP/RTCP version field
            {
                int pt = data[off + 1] & 0xff;

                if (200 <= pt && pt <= 211)
                {
                    return rtcp && acceptRTCP(data, off, len);
                }
                else
                {
                    return !rtcp && acceptRTP(pt & 0x7f);
                }
            }
        }

        return acceptNonRtp && DTLS_DATAGRAM_FILTER.accept(p);
    }

    /**
     * Returns <tt>true</tt> if this <tt>RtpChannelDatagramFilter</tt> should
     * accept an RTCP packet described by <tt>data</tt>, <tt>off</tt>, and
     * <tt>len</tt>.
     *
     * Checks whether the SSRC of the packet sender is an SSRC received on the
     * <tt>channel</tt>.
     * @param data
     * @param len
     * @param off
     * @return
     */
    private boolean acceptRTCP(byte[] data, int off, int len)
    {
        if (checkRtcpSsrc)
        {
            if (len >= 8)
            {
                long packetSenderSSRC
                    = RTPTranslatorImpl.readInt(data, off + 4) & 0xFFFFFFFFL;
                long[] channelSSRCs = channel.receiveSSRCs;

                for (long channelSSRC : channelSSRCs)
                {
                    if (channelSSRC == packetSenderSSRC)
                        return true;
                }
            }
            return false;
        }
        else
        {
            return true;
        }
    }

    /**
     * Returns true if this <tt>RtpChannelDatagramFilter</tt> should accept an
     * RTP packet with a payload type number <tt>pt</tt>.
     *
     * Checks whether <tt>pt</tt> is a payload type configured for the
     * <tt>channel</tt>.
     *
     * @param pt the payload type number of the packet.
     * @return true if this <tt>RtpChannelDatagramFilter</tt> should accept an
     * RTP packet with a payload type number <tt>pt</tt>.
     */
    private boolean acceptRTP(int pt)
    {
        if (checkRtpPayloadType)
        {
            int[] channelPTs = channel.receivePTs;

            for (int channelPT : channelPTs)
            {
                if (channelPT == pt)
                    return true;
            }
            return false;
        }
        else
        {
            return true;
        }
    }

    /**
     * Sets the flag which controls whether to accept non-rtp/rtcp packets.
     * @param acceptNonRtp the value to set.
     */
    public void setAcceptNonRtp(boolean acceptNonRtp)
    {
        this.acceptNonRtp = acceptNonRtp;
    }

    /**
     * Sets the value of the <tt>checkRtcpSsrc</tt> flag.
     * @param checkRtcpSsrc the value to set.
     */
    public void setCheckRtcpSsrc(boolean checkRtcpSsrc)
    {
        this.checkRtcpSsrc = checkRtcpSsrc;
    }

    /**
     * Sets the value of the <tt>checkRtpPayloadType</tt> flag.
     * @param checkRtpPayloadType the value to set.
     */
    public void setCheckRtpPayloadType(boolean checkRtpPayloadType)
    {
        this.checkRtpPayloadType = checkRtpPayloadType;
    }
}

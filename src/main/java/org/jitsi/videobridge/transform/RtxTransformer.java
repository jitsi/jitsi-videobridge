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
package org.jitsi.videobridge.transform;

import java.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 * Intercepts and handles outgoing RTX (RFC-4588) packets for an
 * <tt>RtpChannel</tt>. Depending on whether the destination supports the RTX
 * format (RFC-4588) either removes the RTX encapsulation (thus effectively
 * retransmitting packets bit-by-bit) or updates the sequence number and SSRC
 * fields taking into account the data sent to the particular
 * <tt>RtpChannel</tt>.
 *
 * @author Boris Grozev
 */
public class RtxTransformer
    extends SinglePacketTransformerAdapter
    implements TransformEngine
{
    /**
     * The <tt>Logger</tt> used by the <tt>RtxTransformer</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(RtxTransformer.class);

    /**
     * The <tt>RtpChannel</tt> for the transformer.
     */
    private RtpChannel channel;

    /**
     * Maps an RTX SSRC to the last RTP sequence number sent with that SSRC.
     */
    private final Map<Long, Integer> rtxSequenceNumbers = new HashMap<>();

    /**
     * Initializes a new <tt>RtxTransformer</tt> with a specific
     * <tt>RtpChannel</tt>.
     *
     * @param channel the <tt>RtpChannel</tt> for the transformer.
     */
    RtxTransformer(RtpChannel channel)
    {
        this.channel = channel;
    }

    /**
     * Implements {@link PacketTransformer#transform(RawPacket[])}.
     * {@inheritDoc}
     */
    @Override
    public RawPacket transform(RawPacket pkt)
    {
        byte rtxPt;
        if (pkt != null && (rtxPt = channel.getRtxPayloadType()) != -1
            && pkt.getPayloadType() == rtxPt)
        {
            pkt = handleRtxPacket(pkt);
        }

        return pkt;
    }

    /**
     * Handles an RTX packet and returns it.
     * @param pkt the packet to handle.
     * @return the packet
     */
    private RawPacket handleRtxPacket(RawPacket pkt)
    {
        boolean destinationSupportsRtx = channel.getRtxPayloadType() != -1;
        RawPacket mediaPacket = createMediaPacket(pkt);

        if (mediaPacket != null)
        {
            RawPacketCache cache = channel.getStream().getPacketCache();
            if (cache != null)
            {
                cache.cachePacket(mediaPacket);
            }
        }

        if (destinationSupportsRtx)
        {
            pkt.setSequenceNumber(
                    getNextRtxSequenceNumber(
                            pkt.getSSRC() & 0xffffffffL,
                            pkt.getSequenceNumber()));
        }
        else
        {
            // If the media packet was not reconstructed, drop the RTX packet
            // (by returning null).
            return mediaPacket;
        }

        return pkt;
    }

    /**
     * Creates a {@code RawPacket} which represents the original packet
     * encapsulated in {@code pkt} using the RTX format.
     * @param pkt the packet from which to extract a media packet.
     * @return the extracted media packet.
     */
    private RawPacket createMediaPacket(RawPacket pkt)
    {
        RawPacket mediaPacket = null;
        long rtxSsrc = pkt.getSSRC() & 0xffffffffL;

        // We need to know the SSRC paired with rtxSsrc *as seen by the
        // receiver (i.e. this.channel)*. However, we only store SSRCs
        // that endpoints *send* with.
        // We therefore assume that SSRC re-writing has not introduced any
        // new SSRCs and therefor the FID mappings known to the senders
        // also apply to receivers.
        RtpChannel sourceChannel
                = channel.getContent().findChannelByFidSsrc(rtxSsrc);
        if (sourceChannel != null)
        {
            long mediaSsrc = sourceChannel.getFidPairedSsrc(rtxSsrc);
            if (mediaSsrc != -1)
            {
                byte apt = sourceChannel.getRtxAssociatedPayloadType();
                if (apt != -1)
                {
                    mediaPacket = new RawPacket(pkt.getBuffer().clone(),
                                                pkt.getOffset(),
                                                pkt.getLength());

                    // Remove the RTX header by moving the RTP header two bytes
                    // right.
                    byte[] buf = mediaPacket.getBuffer();
                    int off = mediaPacket.getOffset();
                    System.arraycopy(buf, off,
                                     buf, off + 2,
                                     mediaPacket.getHeaderLength());

                    mediaPacket.setOffset(off + 2);
                    mediaPacket.setLength(pkt.getLength() - 2);

                    mediaPacket.setSSRC((int) mediaSsrc);
                    mediaPacket.setSequenceNumber(
                            pkt.getOriginalSequenceNumber());
                    mediaPacket.setPayloadType(apt);
                }
            }
        }

        return mediaPacket;
    }

    /**
     * Implements {@link TransformEngine#getRTPTransformer()}.
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * Implements {@link TransformEngine#getRTCPTransformer()}.
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

    /**
     * Returns the sequence number to use for a specific RTX packet, which
     * is based on the packet's original sequence number.
     *
     * Because we terminate the RTX format, and with simulcast we might
     * translate RTX packets from multiple SSRCs into the same SSRC, we keep
     * count of the RTX packets (and their sequence numbers) which we sent for
     * each SSRC.
     *
     * @param ssrc the SSRC of the RTX stream for the packet.
     * @param defaultSeq the default sequence number to use in case we don't
     * (yet) have any information about <tt>ssrc</tt>.
     * @return the sequence number which should be used for the next RTX
     * packet sent using SSRC <tt>ssrc</tt>.
     */
    private int getNextRtxSequenceNumber(long ssrc, int defaultSeq)
    {
        Integer seq;
        synchronized (rtxSequenceNumbers)
        {
            seq = rtxSequenceNumbers.get(ssrc);
            if (seq == null)
                seq = defaultSeq;
            else
                seq++;

            rtxSequenceNumbers.put(ssrc, seq);
        }

        return seq;
    }

    /**
     * Returns the next RTP sequence number to use for the RTX stream for a
     * particular SSRC.
     * @param ssrc the SSRC.
     * @return the next sequence number to use for SSRC <tt>ssrc</tt>.
     */
    private int getNextRtxSequenceNumber(long ssrc)
    {
        return getNextRtxSequenceNumber(ssrc, new Random().nextInt(1 << 16));
    }

    /**
     * Tries to find an SSRC paired with {@code ssrc} in an FID group in one
     * of the channels from {@link #channel}'s {@code Content}. Returns -1 on
     * failure.
     * @param ssrc the SSRC for which to find a paired SSRC.
     * @return An SSRC paired with {@code ssrc} in an FID group, or -1.
     */
    private long getPairedSsrc(long ssrc)
    {
        RtpChannel sourceChannel
                = channel.getContent().findChannelByFidSsrc(ssrc);
        if (sourceChannel != null)
        {
            return sourceChannel.getFidPairedSsrc(ssrc);
        }
        return -1;
    }
    /**
     * Retransmits a packet to {@link #channel}. If the destination supports
     * the RTX format, the packet will be encapsulated in RTX, otherwise, the
     * packet will be retransmitted as-is.
     *
     * @param pkt the packet to retransmit.
     * @param after the {@code TransformEngine} in the chain of
     * {@code TransformEngine}s of the associated {@code MediaStream} after
     * which the injection of {@code pkt} is to begin
     * @return {@code true} if the packet was successfully retransmitted,
     * {@code false} otherwise.
     */
    public boolean retransmit(RawPacket pkt, TransformEngine after)
    {
        boolean destinationSupportsRtx = channel.getRtxPayloadType() != -1;
        boolean retransmitPlain;

        if (destinationSupportsRtx)
        {
            long rtxSsrc = getPairedSsrc(pkt.getSSRC());

            if (rtxSsrc == -1)
            {
                logger.warn("Cannot find SSRC for RTX, retransmitting plain.");
                retransmitPlain = true;
            }
            else
            {
                retransmitPlain = !encapsulateInRtxAndTransmit(pkt, rtxSsrc);
            }
        }
        else
        {
            retransmitPlain = true;
        }

        if (retransmitPlain)
        {
            MediaStream mediaStream = channel.getStream();

            if (mediaStream != null)
            {
                try
                {
                    mediaStream.injectPacket(pkt, /* data */ true, after);
                }
                catch (TransmissionFailedException tfe)
                {
                    logger.warn("Failed to retransmit a packet.");
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Encapsulates {@code pkt} in the RTX format, using {@code rtxSsrc} as its
     * SSRC, and transmits it to {@link #channel} by injecting it in the
     * {@code MediaStream}.
     * @param pkt the packet to transmit.
     * @param rtxSsrc the SSRC for the RTX stream.
     * @return {@code true} if the packet was successfully retransmitted,
     * {@code false} otherwise.
     */
    private boolean encapsulateInRtxAndTransmit(RawPacket pkt, long rtxSsrc)
    {
        byte[] buf = pkt.getBuffer();
        int len = pkt.getLength();
        int off = pkt.getOffset();
        byte[] newBuf = buf;
        if (buf.length < len + 2)
        {
            // FIXME The byte array newly allocated and assigned to newBuf must
            // be made known to pkt eventually.
            newBuf = new byte[len + 2];
        }

        int osn = pkt.getSequenceNumber();
        int headerLength = pkt.getHeaderLength();
        int payloadLength = len - headerLength;
        System.arraycopy(buf, off, newBuf, 0, headerLength);
        // FIXME If newBuf is actually buf, then we will override the first two
        // bytes of the payload bellow.
        newBuf[headerLength] = (byte) ((osn >> 8) & 0xff);
        newBuf[headerLength + 1] = (byte) (osn & 0xff);
        System.arraycopy(buf, off + headerLength,
                         newBuf, headerLength + 2,
                         payloadLength );
        // FIXME We tried to extend the payload of pkt by two bytes above but
        // we never told pkt that its length has increased by these two bytes.

        MediaStream mediaStream = channel.getStream();
        if (mediaStream != null)
        {
            pkt.setSSRC((int) rtxSsrc);
            // Only call getNextRtxSequenceNumber() when we're sure we're going
            // to transmit a packet, because it consumes a sequence number.
            pkt.setSequenceNumber(getNextRtxSequenceNumber(rtxSsrc));
            try
            {
                mediaStream.injectPacket(
                        pkt,
                        /* data */ true,
                        /* after */ null);
            }
            catch (TransmissionFailedException tfe)
            {
                logger.warn("Failed to transmit an RTX packet.");
                return false;
            }
        }

        return true;
    }
}

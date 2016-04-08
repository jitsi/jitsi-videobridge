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
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 * Intercepts RTX (RFC-4588) packets coming from an {@link RtpChannel}, and
 * removes their RTX encapsulation.
 * Allows packets to be retransmitted to a channel (using the RTX format if
 * the destination supports it).
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
        super(RTPPacketPredicate.INSTANCE);

        this.channel = channel;
    }

    /**
     * Implements {@link PacketTransformer#transform(RawPacket[])}.
     * {@inheritDoc}
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        if (isRtx(pkt))
        {
            pkt = deRtx(pkt);
        }

        return pkt;
    }

    /**
     * Determines whether {@code pkt} is an RTX packet.
     * @param pkt the packet to check.
     * @return {@code true} iff {@code pkt} is an RTX packet.
     */
    private boolean isRtx(RawPacket pkt)
    {
        byte rtxPt = channel.getRtxPayloadType();
        return rtxPt != -1 && rtxPt == pkt.getPayloadType();
    }

    /**
     * Removes the RTX encapsulation from a packet.
     * @param pkt the packet to remove the RTX encapsulation from.
     * @return the original media packet represented by {@code pkt}, or null if
     * we couldn't reconstruct the original packet.
     */
    private RawPacket deRtx(RawPacket pkt)
    {
        boolean success = false;
        long rtxSsrc = pkt.getSSRCAsLong();

        long mediaSsrc = channel.getFidPairedSsrc(rtxSsrc);
        if (mediaSsrc != -1)
        {
            byte apt = channel.getRtxAssociatedPayloadType();
            if (apt != -1)
            {
                int osn = pkt.getOriginalSequenceNumber();
                // Remove the RTX header by moving the RTP header two bytes
                // right.
                byte[] buf = pkt.getBuffer();
                int off = pkt.getOffset();
                System.arraycopy(buf, off,
                                 buf, off + 2,
                                 pkt.getHeaderLength());

                pkt.setOffset(off + 2);
                pkt.setLength(pkt.getLength() - 2);

                pkt.setSSRC((int) mediaSsrc);
                pkt.setSequenceNumber(osn);
                pkt.setPayloadType(apt);
                success = true;
            }
            else
            {
                logger.warn(
                    "RTX packet received, but no APT is defined. Packet "
                        + "SSRC " + rtxSsrc + ", associated media SSRC "
                        + mediaSsrc);
            }
        }

        // If we failed to handle the RTX packet, drop it.
        return success ? pkt : null;
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
            long rtxSsrc = getPairedSsrc(pkt.getSSRCAsLong());

            if (rtxSsrc == -1)
            {
                logger.warn("Cannot find SSRC for RTX, retransmitting plain. "
                            + "SSRC=" + pkt.getSSRCAsLong());
                retransmitPlain = true;
            }
            else
            {
                retransmitPlain
                    = !encapsulateInRtxAndTransmit(pkt, rtxSsrc, after);
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
     * @param after the {@code TransformEngine} in the chain of
     * {@code TransformEngine}s of the associated {@code MediaStream} after
     * which the injection of {@code pkt} is to begin
     * @return {@code true} if the packet was successfully retransmitted,
     * {@code false} otherwise.
     */
    private boolean encapsulateInRtxAndTransmit(
        RawPacket pkt, long rtxSsrc, TransformEngine after)
    {
        byte[] buf = pkt.getBuffer();
        int len = pkt.getLength();
        int off = pkt.getOffset();

        byte[] newBuf = new byte[len + 2];
        RawPacket rtxPkt = new RawPacket(newBuf, 0, len + 2);

        int osn = pkt.getSequenceNumber();
        int headerLength = pkt.getHeaderLength();
        int payloadLength = pkt.getPayloadLength();

        // Copy the header.
        System.arraycopy(buf, off, newBuf, 0, headerLength);

        // Set the OSN field.
        newBuf[headerLength] = (byte) ((osn >> 8) & 0xff);
        newBuf[headerLength + 1] = (byte) (osn & 0xff);

        // Copy the payload.
        System.arraycopy(buf, off + headerLength,
                         newBuf, headerLength + 2,
                         payloadLength );

        MediaStream mediaStream = channel.getStream();
        if (mediaStream != null)
        {
            rtxPkt.setSSRC((int) rtxSsrc);
            rtxPkt.setPayloadType(channel.getRtxPayloadType());
            // Only call getNextRtxSequenceNumber() when we're sure we're going
            // to transmit a packet, because it consumes a sequence number.
            rtxPkt.setSequenceNumber(getNextRtxSequenceNumber(rtxSsrc));
            try
            {
                mediaStream.injectPacket(
                        rtxPkt,
                        /* data */ true,
                        after);
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

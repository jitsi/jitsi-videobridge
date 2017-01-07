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
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 * Intercepts RTX (RFC-4588) packets coming from an {@link RtpChannel}, and
 * removes their RTX encapsulation.
 * Allows packets to be retransmitted to a channel (using the RTX format if
 * the destination supports it).
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class RtxTransformer
    extends SinglePacketTransformerAdapter
    implements TransformEngine
{
    /**
     * The {@link Logger} used by the {@link RtxTransformer} class to print
     * debug information. Note that {@link Conference} instances should use
     * {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(RtxTransformer.class);

    /**
     * The <tt>RtpChannel</tt> for the transformer.
     */
    private RtpChannel channel;

    /**
     * Maps an RTX SSRC to the last RTP sequence number sent with that SSRC.
     */
    private final Map<Long, Integer> rtxSequenceNumbers = new HashMap<>();

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The payload type number configured for RTX (RFC-4588), or -1 if none is
     * configured (the other end does not support rtx).
     */
    private byte rtxPayloadType = -1;

    /**
     * The "associated payload type" number for RTX.
     */
    private byte rtxAssociatedPayloadType = -1;

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
        this.logger
            = Logger.getLogger(
                    classLogger,
                    channel.getContent().getConference().getLogger());
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
        byte rtxPt = rtxPayloadType;
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

        if (pkt.getPayloadLength() - pkt.getPaddingSize() < 2)
        {
            // We need at least 2 bytes to read the OSN field.
            if (logger.isDebugEnabled())
            {
                logger.debug(
                    "Dropping an incoming RTX packet with padding only: " + pkt);
            }
            return null;
        }

        long mediaSsrc = getPrimarySsrc(pkt);
        if (mediaSsrc != -1)
        {
            if (rtxAssociatedPayloadType != -1)
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
                pkt.setPayloadType(rtxAssociatedPayloadType);
                success = true;
            }
            else
            {
                logger.warn(
                    "RTX packet received, but no APT is defined. Packet "
                        + "SSRC " + pkt.getSSRCAsLong() + ", associated media" +
                        " SSRC " + mediaSsrc);
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
     * @return the sequence number which should be used for the next RTX
     * packet sent using SSRC <tt>ssrc</tt>.
     */
    private int getNextRtxSequenceNumber(long ssrc)
    {
        Integer seq;
        synchronized (rtxSequenceNumbers)
        {
            seq = rtxSequenceNumbers.get(ssrc);
            if (seq == null)
                seq = new Random().nextInt(0xffff);
            else
                seq++;

            rtxSequenceNumbers.put(ssrc, seq);
        }

        return seq;
    }

    /**
     * Tries to find an SSRC paired with {@code ssrc} in an FID group in one
     * of the channels from {@link #channel}'s {@code Content}. Returns -1 on
     * failure.
     * @param pkt the {@code RawPacket} that holds the RTP packet for
     * which to find a paired SSRC.
     * @return An SSRC paired with {@code ssrc} in an FID group, or -1.
     */
    private long getRtxSsrc(RawPacket pkt)
    {
        StreamRTPManager receiveRTPManager = channel
            .getStream()
            .getRTPTranslator()
            .findStreamRTPManagerByReceiveSSRC(pkt.getSSRC());

        MediaStreamTrackReceiver receiver = null;
        if (receiveRTPManager != null)
        {
            MediaStream receiveStream = receiveRTPManager.getMediaStream();
            if (receiveStream != null)
            {
                receiver = receiveStream.getMediaStreamTrackReceiver();
            }
        }

        if (receiver == null)
        {
            return -1;
        }

        RTPEncoding encoding = receiver.resolveRTPEncoding(pkt);
        if (encoding == null)
        {
            logger.warn("encoding_not_found"
                + ",stream_hash=" + channel.getStream().hashCode()
                + " ssrc=" + pkt.getSSRCAsLong());
            return -1;
        }

        return encoding.getRTXSSRC();
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
        boolean destinationSupportsRtx = rtxPayloadType != -1;
        boolean retransmitPlain;

        if (destinationSupportsRtx)
        {
            long rtxSsrc = getRtxSsrc(pkt);

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
     * Notifies this instance that the dynamic payload types of the associated
     * {@link MediaStream} have changed.
     */
    public void onDynamicPayloadTypesChanged()
    {
        rtxPayloadType = -1;
        rtxAssociatedPayloadType = -1;

        MediaStream mediaStream = channel.getStream();

        Map<Byte, MediaFormat> mediaFormatMap
            = mediaStream.getDynamicRTPPayloadTypes();

        Iterator<Map.Entry<Byte, MediaFormat>> it
            = mediaFormatMap.entrySet().iterator();

        while (it.hasNext() && rtxPayloadType == -1)
        {
            Map.Entry<Byte, MediaFormat> entry = it.next();
            MediaFormat format = entry.getValue();
            if (!Constants.RTX.equalsIgnoreCase(format.getEncoding()))
            {
                continue;
            }

            // XXX(gp) we freak out if multiple codecs with RTX support are
            // present.
            rtxPayloadType = entry.getKey();
            rtxAssociatedPayloadType
                = Byte.parseByte(format.getFormatParameters().get("apt"));
        }
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
            rtxPkt.setPayloadType(rtxPayloadType);
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

    /**
     * Returns the SSRC paired with <tt>ssrc</tt> in an FID source-group, if
     * any. If none is found, returns -1.
     *
     * @return the SSRC paired with <tt>ssrc</tt> in an FID source-group, if
     * any. If none is found, returns -1.
     */
    private long getPrimarySsrc(RawPacket pkt)
    {
        MediaStreamTrackReceiver receiver
            = channel.getStream().getMediaStreamTrackReceiver();

        if (receiver == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(
                    "Dropping an incoming RTX packet from an unknown source.");
            }
            return -1;
        }

        RTPEncoding encoding = receiver.resolveRTPEncoding(pkt);
        if (encoding == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(
                    "Dropping an incoming RTX packet from an unknown source.");
            }
            return -1;
        }

        return encoding.getPrimarySSRC();
    }
}

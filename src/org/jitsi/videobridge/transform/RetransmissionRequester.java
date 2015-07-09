/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.io.*;
import java.util.*;

/**
 * Detects lost RTP packets for a particular <tt>RtpChannel</tt> and requests
 * their retransmission by sending RTCP NACK packets.
 *
 * @author Boris Grozev
 */
public class RetransmissionRequester
    extends SinglePacketTransformer
    implements TransformEngine
{
    /**
     * If more than <tt>MAX_MISSING</tt> consecutive packets are lost, we will
     * not request retransmissions for them, but reset our state instead.
     */
    private static final int MAX_MISSING = 30;

    /**
     * The maximum number of retransmission requests to be sent for a single
     * RTP packet.
     */
    private static final int MAX_REQUESTS = 10;

    /**
     * The interval after which another retransmission request will be sent
     * for a packet, unless it arrives. Ideally this should not be a constant,
     * but should be based on the RTT to the endpoint.
     */
    private static final int RE_REQUEST_AFTER = 150;

    /**
     * The <tt>Logger</tt> used by the <tt>RetransmissionRequester</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RetransmissionRequester.class);

    /**
     * Returns the difference between two RTP sequence numbers (modulo 2^16).
     * @return the difference between two RTP sequence numbers (modulo 2^16).
     */
    private static int diff(int a, int b)
    {
        int diff = a - b;
        if (diff < -(1<<15))
            diff += 1<<16;
        else if (diff > 1<<15)
            diff -= 1<<16;

        return diff;
    }

    /**
     * Maps an SSRC to the <tt>Requester</tt> instance corresponding to it.
     * TODO: purge these somehow (RTCP BYE? Timeout?)
     */
    private final Map<Long, Requester> requesters
        = new HashMap<Long, Requester>();

    /**
     * The <tt>RtpChannel</tt> for which this instance works.
     */
    private final RtpChannel channel;

    /**
     * The thread which requests retransmissions by sending RTCP NACK packets.
     */
    private final Thread thread;

    /**
     * Whether this <tt>PacketTransformer</tt> has been closed.
     */
    private boolean closed = false;

    /**
     * Initializes a new <tt>RetransmissionRequester</tt> for the given
     * <tt>RtpChannel</tt>.
     * @param channel the <tt>RtpChannel</tt>.
     */
    RetransmissionRequester(RtpChannel channel)
    {
        this.channel = channel;

        thread = new Thread(){
            @Override
            public void run()
            {
                runInRequesterThread();
            }
        };
        thread.setDaemon(true);
        thread.setName(RetransmissionRequester.class.getName());

        thread.start();
    }

    /**
     * {@inheritDoc}
     *
     * Implements {@link SinglePacketTransformer#transform(RawPacket)}.
     */
    @Override
    public RawPacket transform(RawPacket pkt)
    {
        return pkt;
    }

    /**
     * {@inheritDoc}
     *
     * Implements {@link SinglePacketTransformer#reverseTransform(RawPacket)}.
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        long ssrc = pkt.getSSRC() & 0xffffffffL;
        Requester requester;
        synchronized (requesters)
        {
            requester = requesters.get(ssrc);
            if (requester == null)
            {
                requester = new Requester(ssrc);
                requesters.put(ssrc, requester);
            }
        }
        requester.received(pkt.getSequenceNumber());

        return pkt;
    }

    /**
     * Runs a loop which checks for any pending requests to be sent, sends them
     * and then waits until another request is available or due.
     */
    private void runInRequesterThread()
    {
        if (Thread.currentThread() != thread)
            return;

        Map<Long, Set<Integer>> packetsToRequest = new HashMap<Long, Set<Integer>>();

        while (true)
        {
            if (closed)
                break;

            synchronized (thread)
            {
                // Check when the next request is due. -1 means there is no
                // request scheduled.
                long nextRequestAt = -1;
                synchronized (requesters)
                {
                    for (Requester requester : requesters.values())
                        if (requester.nextRequestAt != -1)
                            if (nextRequestAt == -1 || nextRequestAt > requester.nextRequestAt)
                                nextRequestAt = requester.nextRequestAt;
                }

                long now = System.currentTimeMillis();
                if (nextRequestAt == -1 || nextRequestAt - now > 0)
                {
                    try
                    {
                        if (nextRequestAt == -1)
                            thread.wait();
                        else
                            thread.wait(nextRequestAt - now);
                    }
                    catch (InterruptedException ie)
                    {
                        break;
                    }
                }
                //else the time has already come
            }


            synchronized (requesters)
            {
                for (Map.Entry<Long, Requester> entry : requesters.entrySet())
                {
                    Requester requester = entry.getValue();
                    Set<Integer> missingPackets = requester.getMissing();
                    if (missingPackets != null && !missingPackets.isEmpty())
                    {
                        packetsToRequest.put(requester.ssrc, missingPackets);
                    }
                }
            }


            //Send NACK packets
            MediaStream mediaStream = channel.getStream();
            if (mediaStream != null)
            {
                long senderSsrc = channel.getContent().getInitialLocalSSRC();
                for (Map.Entry<Long, Set<Integer>> entry : packetsToRequest.entrySet())
                {
                    long sourceSsrc = entry.getKey();
                    NACKPacket nack  = new NACKPacket(
                            senderSsrc, sourceSsrc, entry.getValue());

                    RawPacket pkt = null;
                    try
                    {
                        pkt = nack.toRawPacket();
                    }
                    catch (IOException ioe)
                    {
                        logger.warn("Failed to create a NACK packet: " + ioe);
                    }

                    if (pkt != null)
                        mediaStream.injectPacket(pkt, false, true);
                }
            }

            packetsToRequest.clear();
        }
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
     * Handles packets for a single SSRC.
     */
    private class Requester
    {
        /**
         * The SSRC for this instance.
         */
        private final long ssrc;

        /**
         * The highest received RTP sequence number.
         */
        private int lastReceivedSeq = -1;

        /**
         * The time that the next request for this SSRC should be sent.
         */
        private long nextRequestAt = -1;

        /**
         * The set of active requests for this SSRC. The keys are the sequence
         * numbers.
         */
        private final Map<Integer, Request> requests
            = new HashMap<Integer, Request>();

        /**
         * Initializes a new <tt>Requester</tt> instance for the given SSRC.
         */
        private Requester(long ssrc)
        {
            this.ssrc = ssrc;
        }

        /**
         * Handles a received RTP packet with a specific sequence number.
         * @param seq the RTP sequence number of the received packet.
         */
        synchronized private void received(int seq)
        {
            if (lastReceivedSeq == -1)
            {
                lastReceivedSeq = seq;
                return;
            }

            int diff = diff(seq, lastReceivedSeq);
            if (diff <= 0)
            {
                // An older packet, possibly already requested.
                // We don't update nextRequestAt here. The reading thread might
                // wake up unnecessarily and do some extra work, but that's OK.
                requests.remove(seq);
            }
            else if (diff == 1)
            {
                // The very next packet, as expected.
                lastReceivedSeq = seq;
            }
            else if (diff <= MAX_MISSING)
            {
                for (int missing = (lastReceivedSeq + 1) % (1<<16);
                     missing != seq;
                     missing = (missing + 1) % (1<<16))
                {
                    Request request = new Request(missing);
                    requests.put(missing, request);
                }

                lastReceivedSeq = seq;
                nextRequestAt = 0;

                synchronized (thread)
                {
                    thread.notifyAll();
                }
            }
            else // if (diff > MAX_MISSING)
            {
                // Too many packets missing. Reset.
                lastReceivedSeq = seq;
                if (logger.isDebugEnabled())
                {
                    logger.debug("Resetting retransmission requester state. "
                                 + "SSRC: " + ssrc
                                 + ", last received: " + lastReceivedSeq
                                 + ", current: " + seq
                                 + ". Removing " + requests.size()
                                 + " unsatisfied requests.");
                }
                requests.clear();
                nextRequestAt = -1;
            }

        }

        /**
         * Returns a set of RTP sequence numbers which are considered still MIA,
         * and for which a retransmission request needs to be sent.
         * Assumes that the returned set of sequence numbers will be requested
         * immediately and updates the state accordingly (i.e. increments the
         * timesRequested counters and sets the time of next request).
         *
         * @return a set of RTP sequence numbers which are considered still MIA,
         * and for which a retransmission request needs to be sent.
         */
        synchronized private Set<Integer> getMissing()
        {
            long now = System.currentTimeMillis();
            Set<Integer> missingPackets = null;

            if (nextRequestAt == -1 || nextRequestAt > now)
                return null;

            for (Iterator<Map.Entry<Integer,Request>> iter
                        = requests.entrySet().iterator();
                 iter.hasNext();
                    )
            {
                Request request = iter.next().getValue();

                if (missingPackets == null)
                    missingPackets = new HashSet<Integer>();

                missingPackets.add(request.seq);
                request.timesRequested++;

                if (request.timesRequested == 1)
                    request.firstRequestSentAt = now;
                else if (request.timesRequested == MAX_REQUESTS)
                {
                    logger.info("Sending the last NACK for SSRC=" + ssrc
                                 + " seq=" + request.seq + ". "
                                 + "Time since the first request: "
                                 + (now - request.firstRequestSentAt));
                    iter.remove();
                }

            }

            nextRequestAt =
                    (requests.size() > 0)
                    ? now + RE_REQUEST_AFTER
                    : -1;

            return missingPackets;
        }
    }

    /**
     * Represents a request for the retransmission of a specific RTP packet.
     */
    private class Request
    {
        /**
         * The RTP sequence number.
         */
        private int seq;

        /**
         * The system time at the moment a retransmission request for this
         * packet was first sent.
         */
        private long firstRequestSentAt = -1;

        /**
         * The number of times that a retransmission request for this packet
         * has been sent.
         */
        private int timesRequested = 0;

        /**
         * Initializes a new <tt>Request</tt> instance with the given RTP
         * sequence number.
         * @param seq the RTP sequence number.
         */
        private Request(int seq)
        {
            this.seq = seq;
        }
    }
}

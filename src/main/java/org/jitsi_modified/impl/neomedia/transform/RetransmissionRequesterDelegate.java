/*
 * Copyright @ 2017 Atlassian Pty Ltd
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
package org.jitsi_modified.impl.neomedia.transform;

import java.util.*;
import java.util.function.*;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.util.*;
import org.jitsi.nlj.util.TimeProvider;
import org.jitsi.rtp.rtcp.rtcpfb.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;

/**
 * Detects lost RTP packets for a particular <tt>RtpChannel</tt> and requests
 * their retransmission by sending RTCP NACK packets.
 *
 * @author Boris Grozev
 * @author George Politis
 * @author bbaldino
 */
public class RetransmissionRequesterDelegate
        implements RecurringRunnable
{
    /**
     * If more than <tt>MAX_MISSING</tt> consecutive packets are lost, we will
     * not request retransmissions for them, but reset our state instead.
     */
    public static final int MAX_MISSING = 100;

    /**
     * The maximum number of retransmission requests to be sent for a single
     * RTP packet.
     */
    public static final int MAX_REQUESTS = 10;

    /**
     * The interval after which another retransmission request will be sent
     * for a packet, unless it arrives. Ideally this should not be a constant,
     * but should be based on the RTT to the endpoint.
     */
    public static final int RE_REQUEST_AFTER_MILLIS = 150;

    /**
     * The interval we'll ask the {@link RecurringRunnableExecutor} to check back
     * in if there is no current work
     * TODO(brian): i think we should actually be able to get rid of this and
     * just rely on scheduled work and the 'work ready now' callback
     */
    public static final long WAKEUP_INTERVAL_MILLIS = 1000;

    /**
     * The <tt>Logger</tt> used by the <tt>RetransmissionRequesterDelegate</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(RetransmissionRequesterDelegate.class);

    /**
     * Maps an SSRC to the <tt>Requester</tt> instance corresponding to it.
     * TODO: purge these somehow (RTCP BYE? Timeout?)
     */
    private final Map<Long, Requester> requesters = new HashMap<>();

    /**
     * The {@link MediaStream} that this instance belongs to.
     */
//    private final MediaStream stream;

    /**
     * The SSRC which will be used as Packet Sender SSRC in NACK packets sent
     * by this {@code RetransmissionRequesterDelegate}.
     */
    private long senderSsrc = -1;


    protected final TimeProvider timeProvider;

    public Consumer<RawPacket> rtcpSender;

    /**
     * A callback which allows this class to signal it has nack work that is ready
     * to be run
     */
    protected Runnable workReadyCallback = null;

    /**
     * Initializes a new <tt>RetransmissionRequesterDelegate</tt> for the given
     * <tt>RtpChannel</tt>.
//     * @param stream the {@link MediaStream} that the instance belongs to.
     */
    public RetransmissionRequesterDelegate(/*MediaStream stream, */TimeProvider timeProvider)
    {
//        this.stream = stream;
        this.timeProvider = timeProvider;
    }

    public RetransmissionRequesterDelegate()
    {
        this(new SystemTimeProvider());
    }

    /**
     * Notify this requester that a packet has been received
     */
    public void packetReceived(long ssrc, int seqNum)
    {
        // TODO(gp) Don't NACK higher temporal layers.
        Requester requester = getOrCreateRequester(ssrc);
        // If the reception of this packet resulted in there being work that
        // is ready to be done now, fire the work ready callback
        if (requester.received(seqNum))
        {
//            System.out.println("BRIAN: work to be done, invoking work ready callback");
            if (workReadyCallback != null)
            {
                workReadyCallback.run();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTimeUntilNextRun()
    {
        long now = timeProvider.currentTimeMillis();
        Requester nextDueRequester = getNextDueRequester();
        if (nextDueRequester == null)
        {
            return WAKEUP_INTERVAL_MILLIS;
        }
        else
        {
//            if (logger.isTraceEnabled())
//            {
//                logger.trace(hashCode() + ": Next nack is scheduled for ssrc " +
//                        nextDueRequester.ssrc + " at " +
//                        Math.max(nextDueRequester.nextRequestAt, 0) +
//                        ".  (current time is " + now + ")");
//            }
            return Math.max(nextDueRequester.nextRequestAt - now, 0);
        }
    }

    public void setWorkReadyCallback(Runnable workReadyCallback)
    {
        this.workReadyCallback = workReadyCallback;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run()
    {
//        System.out.println("BRIAN: retransmission requester run loop executing");
        long now = timeProvider.currentTimeMillis();
//        if (logger.isTraceEnabled())
//        {
//            logger.trace(hashCode() + " running at " + now);
//        }
        List<Requester> dueRequesters = getDueRequesters(now);
//        if (logger.isTraceEnabled())
//        {
//            logger.trace(hashCode() + " has " + dueRequesters.size() + " due requesters");
//        }
        if (!dueRequesters.isEmpty())
        {
//            System.out.println("Have due requesters");
            List<RtcpFbNackPacket> nackPackets = createNackPackets(now, dueRequesters);
//            if (logger.isTraceEnabled())
//            {
//                logger.trace(hashCode() + " injecting " + nackPackets.size() + " nack packets");
//            }
            if (!nackPackets.isEmpty())
            {
//                System.out.println("Created nack packets");
                injectNackPackets(nackPackets);
            }
        }
    }

    private Requester getOrCreateRequester(long ssrc)
    {
        Requester requester;
        synchronized (requesters)
        {
            requester = requesters.get(ssrc);
            if (requester == null)
            {
//                if (logger.isDebugEnabled())
//                {
//                    logger.debug(
//                            "Creating new Requester for SSRC " + ssrc);
//                }
                requester = new Requester(ssrc);
                requesters.put(ssrc, requester);
            }
        }
        return requester;
    }

    private Requester getNextDueRequester()
    {
        Requester nextDueRequester = null;
        synchronized (requesters)
        {
            for (Requester requester : requesters.values())
            {
                if (requester.nextRequestAt != -1 &&
                        (nextDueRequester == null || requester.nextRequestAt < nextDueRequester.nextRequestAt))
                {
                    nextDueRequester = requester;
                }
            }
        }
        return nextDueRequester;
    }

    /**
     * Get a list of the requesters (not necessarily in sorted order)
     * which are due to request as of the given time
     *
     * @param currentTime the current time
     * @return a list of the requesters (not necessarily in sorted order)
     * which are due to request as of the given time
     */
    private List<Requester> getDueRequesters(long currentTime)
    {
        List<Requester> dueRequesters = new ArrayList<>();
        synchronized (requesters)
        {
            for (Requester requester : requesters.values())
            {
                if (requester.isDue(currentTime))
                {
                    if (logger.isTraceEnabled())
                    {
                        logger.trace(hashCode() + " requester for ssrc " +
                                requester.ssrc + " has work due at " +
                                requester.nextRequestAt +
                                " (now = " + currentTime + ") and is missing packets: " +
                                requester.getMissingSeqNums());
                    }
                    dueRequesters.add(requester);
                }
            }
        }
        return dueRequesters;
    }

    /**
     * Inject the given nack packets into the outgoing stream
     * @param nackPackets the nack packets to inject
     */
    private void injectNackPackets(List<RtcpFbNackPacket> nackPackets)
    {
        for (RtcpFbNackPacket nackPacket : nackPackets)
        {
//            try
//            {
                RawPacket packet;
//                try
//                {
                    packet = PacketExtensionsKt.toRawPacket(nackPacket);
//                }
//                catch (IOException ioe)
//                {
//                    logger.warn("Failed to create a NACK packet: " + ioe);
//                    continue;
//                }

//                if (logger.isTraceEnabled())
//                {
//                    logger.trace("Sending a NACK: " + nackPacket);
//                }

                rtcpSender.accept(packet);
//                stream.injectPacket(packet, /* data */ false, /* after */ null);
//            }
//            catch (TransmissionFailedException e)
//            {
//                logger.warn(
//                        "Failed to inject packet in MediaStream: ", e.getCause());
//            }
        }
    }

    /**
     * Gather the packets currently marked as missing and create
     * NACKs for them
     * @param dueRequesters the requesters which are due to have nack packets
     * generated
     */
    protected List<RtcpFbNackPacket> createNackPackets(long now, List<Requester> dueRequesters)
    {
        Map<Long, Set<Integer>> packetsToRequest = new HashMap<>();

        for (Requester dueRequester : dueRequesters)
        {
            synchronized (dueRequester)
            {
                Set<Integer> missingPackets = dueRequester.getMissingSeqNums();
                if (!missingPackets.isEmpty())
                {
                    if (logger.isTraceEnabled())
                    {
                        logger.trace(
                                hashCode() + " Sending nack with packets "
                                        + missingPackets
                                        + " for ssrc " + dueRequester.ssrc);

                    }
                    packetsToRequest.put(dueRequester.ssrc, missingPackets);
                    dueRequester.notifyNackCreated(now, missingPackets);
                }
            }
        }

        List<RtcpFbNackPacket> nackPackets = new ArrayList<>();
        for (Map.Entry<Long, Set<Integer>> entry : packetsToRequest.entrySet())
        {
            long sourceSsrc = entry.getKey();
            Set<Integer> missingPackets = entry.getValue();
            RtcpFbNackPacket nack
                    = new RtcpFbNackPacket(sourceSsrc, new ArrayList<>(missingPackets));
            nackPackets.add(nack);
        }
        return nackPackets;
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
        private final Map<Integer, Request> requests = new HashMap<>();

        /**
         * Initializes a new <tt>Requester</tt> instance for the given SSRC.
         */
        private Requester(long ssrc)
        {
            this.ssrc = ssrc;
        }

        /**
         * Check if this {@link Requester} is due to send a nack
         * @param currentTime the current time, in ms
         * @return true if this {@link Requester} is due to send a nack, false
         * otherwise
         */
        public boolean isDue(long currentTime)
        {
            return nextRequestAt != -1 && nextRequestAt <= currentTime;
        }

        /**
         * Handles a received RTP packet with a specific sequence number.
         * @param seq the RTP sequence number of the received packet.
         *
         * @return true if there is work for this requester ready to be
         * done now, false otherwise
         */
        synchronized private boolean received(int seq)
        {
            if (lastReceivedSeq == -1)
            {
                lastReceivedSeq = seq;
                return false;
            }

            int diff = RTPUtils.getSequenceNumberDelta(seq, lastReceivedSeq);
            if (diff <= 0)
            {
                // An older packet, possibly already requested.
                Request r = requests.remove(seq);
                if (requests.isEmpty())
                {
                    nextRequestAt = -1;
                }

//                if (r != null && logger.isDebugEnabled())
//                {
//                    long rtt
//                            = stream.getMediaStreamStats().getSendStats().getRtt();
//                    if (rtt > 0)
//                    {
//
//                        // firstRequestSentAt is if we created a Request, but
//                        // haven't yet sent a NACK. Assume a delta of 0 in that
//                        // case.
//                        long firstRequestSentAt = r.firstRequestSentAt;
//                        long delta
//                                = firstRequestSentAt > 0
//                                ? timeProvider.currentTimeMillis() - r.firstRequestSentAt
//                                : 0;
//
//                        logger.debug(Logger.Category.STATISTICS,
//                                "retr_received,stream=" + stream
//                                        .hashCode() +
//                                        " delay=" + delta +
//                                        ",rtt=" + rtt);
//                    }
//                }
            }
            else if (diff == 1)
            {
                // The very next packet, as expected.
                lastReceivedSeq = seq;
            }
            else if (diff <= MAX_MISSING)
            {
                System.out.println("BRIAN: missing packet detected! ssrc " + ssrc + " just received " + seq +
                        ", last received was: " + lastReceivedSeq);
                for (int missing = (lastReceivedSeq + 1) % (1<<16);
                     missing != seq;
                     missing = (missing + 1) % (1<<16))
                {
                    Request request = new Request(missing);
                    requests.put(missing, request);
                }

                lastReceivedSeq = seq;
                nextRequestAt = 0;

                return true;
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
            return false;
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
        synchronized private @NotNull Set<Integer> getMissingSeqNums()
        {
            return new HashSet<>(requests.keySet());
        }

        /**
         * Notify this requester that a nack was sent at the given time
         * @param time the time at which the nack was sent
         */
        public synchronized void notifyNackCreated(long time, Collection<Integer> sequenceNumbers)
        {
            for (Integer seqNum : sequenceNumbers)
            {
                Request request = requests.get(seqNum);
                request.timesRequested++;
                if (request.timesRequested == MAX_REQUESTS)
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug(
                                "Generated the last NACK for SSRC=" + ssrc + " seq="
                                        + request.seq + ". "
                                        + "Time since the first request: "
                                        + (time - request.firstRequestSentAt));
                    }
                    requests.remove(seqNum);
                    continue;
                }
                if (request.timesRequested == 1)
                {
                    request.firstRequestSentAt = time;
                }
            }
            nextRequestAt = (requests.size() > 0) ? time + RE_REQUEST_AFTER_MILLIS : -1;
        }

    }

    /**
     * Represents a request for the retransmission of a specific RTP packet.
     */
    private static class Request
    {
        /**
         * The RTP sequence number.
         */
        final int seq;

        /**
         * The system time at the moment a retransmission request for this
         * packet was first sent.
         */
        long firstRequestSentAt = -1;

        /**
         * The number of times that a retransmission request for this packet
         * has been sent.
         */
        int timesRequested = 0;

        /**
         * Initializes a new <tt>Request</tt> instance with the given RTP
         * sequence number.
         * @param seq the RTP sequence number.
         */
        Request(int seq)
        {
            this.seq = seq;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setSenderSsrc(long ssrc)
    {
        senderSsrc = ssrc;
    }
}

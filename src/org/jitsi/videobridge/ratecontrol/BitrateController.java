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
package org.jitsi.videobridge.ratecontrol;

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;

/**
 * Controls the bitrate of a specific <tt>VideoChannel</tt>.
 * <p>
 * Gets notified of received RTCP REMB packets through
 * {@link #receivedREMB(long)}. Based on this information (the estimation of
 * the available bandwidth to the endpoint of the <tt>VideoChannel</tt>) and on
 * the recent average bitrates coming from the other endpoints in the conference
 * decides whether the bitrate of the channel should be changed. A specialized
 * bitrate adaptor performs the change.
 * </p>
 * <p>
 * The specific logic used to make the decision is implemented and documented in
 * {@link #receivedREMB(long)}.
 * </p>
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class BitrateController
{
    /**
     * Whether the values for the constants have been initialized or not.
     */
    private static boolean configurationInitialized = false;

    /**
     * The constant that specifies the minimum amount of time in milliseconds
     * that we wait before decreasing lastN. That is, we only decrease lastN if
     * for the last <tt>DECREASE_LAG_MS</tt> milliseconds we have not received a
     * REMB indicating that we should increase lastN or keep it as it is.
     */
    private static int DECREASE_LAG_MS = 10000;

    /**
     * The name of the property which can be used to control the
     * <tt>DECREASE_LAG_MS</tt> constant.
     */
    private static final String DECREASE_LAG_MS_PNAME
            = BitrateController.class.getName() + ".DECREASE_LAG_MS";

    /**
     * The constant that specifies the minimum amount of time in milliseconds
     * that we wait before increasing lastN. That is, we only increase lastN if
     * for the last <tt>INCREASE_LAG_MS</tt> milliseconds we have not received a
     * REMB indicating that we should decrease lastN or keep it as it is.
     */
    private static int INCREASE_LAG_MS = 20000;

    /**
     * The name of the property which can be used to control the
     * <tt>INCREASE_LAG_MS</tt> constant.
     */
    private static final String INCREASE_LAG_MS_PNAME
            = BitrateController.class.getName() + ".INCREASE_LAG_MS";

    /**
     * The constant that specifies the initial period during which we will not
     * perform lastN adaptation.
     */
    private static int INITIAL_INTERVAL_MS = 70000;

    /**
     * The name of the property which can be used to control the
     * <tt>INITIAL_INTERVAL_MS</tt> constant.
     */
    private static final String INITIAL_INTERVAL_MS_PNAME
            = BitrateController.class.getName() + ".INITIAL_INTERVAL_MS";

    /**
     * The <tt>Logger</tt> used by the <tt>BitrateController</tt> class
     * and its instances to print debug information.
     */
    @SuppressWarnings("unused")
    private static final org.jitsi.util.Logger logger
        = org.jitsi.util.Logger.getLogger(BitrateController.class);

    /**
     * The minimum bitrate in bits per second to assume for an endpoint.
     */
    private static int MIN_ASSUMED_ENDPOINT_BITRATE_BPS = 400000;

    /**
     * The name of the property which can be used to control the
     * <tt>MIN_ASSUMED_ENDPOINT_BITRATE_BPS</tt> constant.
     */
    private static final String MIN_ASSUMED_ENDPOINT_BITRATE_BPS_PNAME
            = BitrateController.class.getName()
            + ".MIN_ASSUMED_ENDPOINT_BITRATE_BPS";

    /**
     * The interval over which the average REMB values will be used.
     */
    private static int REMB_AVERAGE_INTERVAL_MS = 5000;

    /**
     * The name of the property which can be used to control the
     * <tt>REMB_AVERAGE_INTERVAL_MS</tt> constant.
     */
    private static final String REMB_AVERAGE_INTERVAL_MS_PNAME
            = BitrateController.class.getName() + ".REMB_AVERAGE_INTERVAL_MS";

    /**
     * The constant that we multiply the received REMB by before calculating the
     * number of endpoints that this endpoint can receive.
     */
    private static double REMB_MULT_CONSTANT = 1D;
    
    /**
     * The name of the property which can be used to control the
     * <tt>REMB_MULT_CONSTANT</tt> constant.
     */
    private static final String REMB_MULT_CONSTANT_PNAME
            = BitrateController.class.getName() + ".REMB_MULT_CONSTANT";

    /**
     * The <tt>BitrateAdaptor</tt> to use to adapt the bandwidth.
     */
    private BitrateAdaptor bitrateAdaptor;
    
    /**
     * Whether this <tt>BitrateController</tt> has set its
     * <tt>BitrateAdaptor</tt>.
     */
    private boolean bitrateAdaptorSet = false;

    /**
     * The <tt>VideoChannel</tt> of this <tt>BitrateController</tt>.
     */
    private final VideoChannel channel;

    /**
     * The time of reception of first REMB packet.
     */
    private long firstRemb = -1;

    /**
     * The time of reception of the last REMB which indicated that we can
     * increase lastN or keep it as it is (but not decrease it).
     */
    private long lastNonDecrease = -1;

    /**
     * The time of reception of the last REMB which indicated that we can
     * decrease lastN or keep it as it is (but not increase it).
     */
    private long lastNonIncrease = -1;
    
    /**
     * The list of recently received REMB values, used to compute the average
     * over the last <tt>REMB_AVERAGE_INTERVAL_MS</tt>.
     */
    private final ReceivedRembList receivedRembs
            = new ReceivedRembList(REMB_AVERAGE_INTERVAL_MS);

    /**
     * Initializes a new <tt>BitrateController</tt> instance.
     *
     * @param channel the <tt>VideoChannel</tt> for which the new instance is to
     * serve.
     */
    public BitrateController(VideoChannel channel)
    {
        this.channel = channel;

        initializeConfiguration();
    }

    public int calcNumEndpointsThatFitIn()
    {
        final long now = System.currentTimeMillis();

        // Our estimate of the available bandwidth is the average of all
        // REMBs received in the last REMB_AVERAGE_INTERVAL_MS milliseconds. We
        // do this in order to reduce the fluctuations, because REMBs often
        // change very rapidly and we want to avoid changing lastN often.
        // Multiplying with a constant is an experimental option.
        final long availableBandwidth
                = (long) (receivedRembs.getAverage(now) * REMB_MULT_CONSTANT);

        // Calculate the biggest number K, such that there are at least K other
        // endpoints in the conference, and the cumulative bitrate of the first
        // K endpoints does not exceed the available bandwidth estimate.

        long remainingBandwidth = availableBandwidth;
        int numEndpointsThatFitIn = 0;

        final Iterator<Endpoint> it = channel.getReceivingEndpoints();
        final Endpoint thisEndpoint = channel.getEndpoint();

        while (it.hasNext())
        {
            Endpoint endpoint = it.next();

            if (endpoint != null && !endpoint.equals(thisEndpoint))
            {
                long endpointBitrate = getEndpointBitrate(endpoint);

                if (remainingBandwidth >= endpointBitrate)
                {
                    numEndpointsThatFitIn += 1;
                    remainingBandwidth -= endpointBitrate;
                }
                else
                {
                    break;
                }
            }
        }

        return numEndpointsThatFitIn;
    }

    /**
     * Gets the <tt>VideoChannel</tt> of this <tt>BitrateController</tt>.
     *
     * @return the <tt>VideoChannel</tt> of this <tt>BitrateController</tt>.
     */
    public VideoChannel getChannel()
    {
        return channel;
    }

    /**
     * Returns the incoming bitrate in bits per second from all
     * <tt>VideoChannel</tt>s of the endpoint <tt>endpoint</tt> or
     * {@link #MIN_ASSUMED_ENDPOINT_BITRATE_BPS} if the actual bitrate is that
     * limit.
     *
     * @param endpoint the endpoint.
     * @return the incoming bitrate in bits per second from <tt>endpoint</tt>,
     * or {@link #MIN_ASSUMED_ENDPOINT_BITRATE_BPS} if the actual bitrate is
     * below that limit.
     */
    private long getEndpointBitrate(Endpoint endpoint)
    {
        SimulcastManager mySM = this.channel.getSimulcastManager();
        long bitrate = 0;

        for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
        {
            if (channel != null && channel instanceof VideoChannel)
            {
                VideoChannel vc = (VideoChannel) channel;
                SimulcastManager peerSM = vc.getSimulcastManager();
                if (mySM != null && peerSM != null && peerSM.hasLayers())
                {
                    bitrate += mySM.getIncomingBitrate(peerSM, true);
                }
                else
                {
                    bitrate += ((VideoChannel) channel).getIncomingBitrate();
                }
            }
        }
        return Math.max(bitrate, MIN_ASSUMED_ENDPOINT_BITRATE_BPS);
    }

    /**
     * Gets, and creates if necessary, the <tt>BitrateAdaptor</tt> of this
     * <tt>BitrateController</tt>.
     *
     * @return the <tt>BitrateAdaptor</tt> of this <tt>BitrateController</tt>.
     */
    private BitrateAdaptor getOrCreateBitrateAdaptor()
    {
        if (bitrateAdaptor == null && !bitrateAdaptorSet)
        {
            bitrateAdaptorSet = true;

            if (channel.getAdaptiveLastN())
            {
                bitrateAdaptor = new VideoChannelLastNAdaptor(this);
            }
            else if (channel.getAdaptiveSimulcast())
            {
                bitrateAdaptor = new SimulcastAdaptor(this);
            }
        }

        return bitrateAdaptor;
    }
    /**
     * Initializes the constants used by this class from the configuration.
     */
    private void initializeConfiguration()
    {
        synchronized (BitrateController.class)
        {
            if (configurationInitialized)
                return;
            configurationInitialized = true;

            ConfigurationService cfg
                    = ServiceUtils.getService(
                    channel.getBundleContext(),
                    ConfigurationService.class);

            if (cfg != null)
            {
                INCREASE_LAG_MS
                        = cfg.getInt(INCREASE_LAG_MS_PNAME, INCREASE_LAG_MS);
                INCREASE_LAG_MS
                        = cfg.getInt(DECREASE_LAG_MS_PNAME, DECREASE_LAG_MS);
                INITIAL_INTERVAL_MS
                        = cfg.getInt(
                        INITIAL_INTERVAL_MS_PNAME,
                        INITIAL_INTERVAL_MS);

                String rembMultConstantStr
                        = cfg.getString(REMB_MULT_CONSTANT_PNAME, null);

                if (rembMultConstantStr != null)
                {
                    try
                    {
                        REMB_MULT_CONSTANT
                                = Double.parseDouble(rembMultConstantStr);
                    }
                    catch (Exception e)
                    {
                        // Whatever, use the default
                    }
                }

                REMB_AVERAGE_INTERVAL_MS
                        = cfg.getInt(
                        REMB_AVERAGE_INTERVAL_MS_PNAME,
                        REMB_AVERAGE_INTERVAL_MS);
                MIN_ASSUMED_ENDPOINT_BITRATE_BPS
                        = cfg.getInt(
                        MIN_ASSUMED_ENDPOINT_BITRATE_BPS_PNAME,
                        MIN_ASSUMED_ENDPOINT_BITRATE_BPS);
            }
        }
    }


    /**
     * Notifies this instance that an RTCP REMB packet with a bitrate value of
     * <tt>remb</tt> was received on its associated <tt>VideoChannel</tt>.
     *
     * @param remb the bitrate of the REMB packet received.
     */
    public void receivedREMB(long remb)
    {
        BitrateAdaptor bitrateAdaptor = getOrCreateBitrateAdaptor();
        if (bitrateAdaptor == null)
        {
            // A bitrate adaptor is not set. It makes no sense to continue.
            return;
        }

        long now = System.currentTimeMillis();

        // The number of endpoints this channel is currently receiving
        int receivingEndpointCount = channel.getReceivingEndpointCount();

        if (firstRemb == -1)
            firstRemb = now;

        // Update the list of received values, so that the new value is taken
        // into account in the average taken below.
        receivedRembs.add(now, remb);

        // Do not change lastN in the initial interval (give time to the
        // incoming REMBs to "ramp-up").
        if (now - firstRemb <= INITIAL_INTERVAL_MS)
            return;

        // Touch the adaptor and give it a chance to prevent bitrate adaptation.
        if (!bitrateAdaptor.touch())
            return;

        int numEndpointsThatFitIn = calcNumEndpointsThatFitIn();
        if (numEndpointsThatFitIn < receivingEndpointCount)
        {
            lastNonIncrease = now;

            // Only do the actual decrease if in the last DECREASE_LAG_MS
            // we have always seen that the first 'lastN' endpoints generate
            // higher bitrate than our estimate of the available bandwidth.
            if (now - lastNonDecrease >= DECREASE_LAG_MS)
            {
                // Avoid quick "consecutive" decreases
                lastNonDecrease = now;

                bitrateAdaptor.decrease();
            }
        }
        else if (numEndpointsThatFitIn == receivingEndpointCount)
        {
            lastNonDecrease = now;

            // We do not update this, because otherwise when a new participant
            // join we would delay its video by INCREASE_LAG_MS
            //lastNonIncrease = now;
        }
        else if (numEndpointsThatFitIn > receivingEndpointCount)
        {
            lastNonDecrease = now;

            // Only do the actual increase if in the last INCREASE_LAG_MS
            // we have always seen that the estimated available bandwidth is
            // enough to accommodate at least 'lastN'+1 endpoints.
            if (now - lastNonIncrease >= INCREASE_LAG_MS)
            {
                // Avoid quick "consecutive" increases
                lastNonIncrease = now;
                bitrateAdaptor.increase();
            }
        }
    }

    /**
     * Saves the received REMB values along with their time of reception and
     * allows getting the average value over a certain period.
     *
     * TODO: maybe re-implement in a more efficient way.
     *
     * @author Boris Grozev
     */
    private static class ReceivedRembList
    {
        /**
         * The period in milliseconds for which values will be saved.
         */
        private final long period;

        /**
         * Maps a time of reception to a received value.
         */
        private final Map<Long, Long> receivedRembs
                = new HashMap<Long, Long>();

        /**
         * The sum of all values in this list.
         */
        private long sum = 0;

        /**
         * Used in {@link #clean(long)}.
         */
        private final List<Long> toRemove = new ArrayList<Long>();

        /**
         * Initializes a new <tt>ReceivedRembList</tt> with the given period in
         * milliseconds.
         *
         * @param period the period to save values and compute the average for.
         */
        private ReceivedRembList(long period)
        {
            this.period = period;
        }

        /**
         * Adds a received value to this list.
         *
         * @param time the time of reception.
         * @param rate the value.
         */
        private void add(long time, long rate)
        {
            sum += rate;
            receivedRembs.put(time, rate);
            clean(time);
        }

        /**
         * Removes values added before <tt>time - period</tt>.
         */
        private void clean(long time)
        {
            long oldest = time - period;

            toRemove.clear();
            for (Map.Entry<Long,Long> entry : receivedRembs.entrySet())
            {
                long t = entry.getKey();

                if (t < oldest)
                    toRemove.add(t);
            }

            for (long t : toRemove)
                sum -= receivedRembs.remove(t);
        }

        /**
         * Gets the average of the values in this with timestamps between
         * <tt>time - period</tt> and <tt>time</tt>.
         *
         * @param time the time
         * @return  the average of the values in this with timestamps between
         * <tt>time - period</tt> and <tt>time</tt>.
         */
        private long getAverage(long time)
        {
            clean(time);

            int size = receivedRembs.size();

            return (size == 0) ? 0 : (sum / size);
        }
    }
}

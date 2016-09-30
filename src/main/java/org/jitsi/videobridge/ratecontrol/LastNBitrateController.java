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
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.concurrent.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;

/**
 * <p>
 * Gets notified of changes to the estimation of the available bandwidth towards
 * the remote endpoint through {@link #bandwidthEstimationChanged(long)}. Based
 * on this information, and on the current average bitrates coming from the
 * other endpoints in the conference decides whether the configuration of the
 * channel (i.e. the set of forwarded endpoints) should be changed.
 * </p>
 * <p>
 * The specific logic used to make the decision is implemented and documented in
 * {@link #bandwidthEstimationChanged(long)}.
 * </p>
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class LastNBitrateController
    extends BitrateController
    implements BandwidthEstimator.Listener,
               RecurringRunnable
{
    /**
     * Whether the values for the constants have been initialized or not.
     */
    private static boolean configurationInitialized = false;

    /**
     * The interval at which {@link #run()} should be called, in
     * milliseconds.
     */
    private static final int PROCESS_INTERVAL_MS = 200;

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
            = LastNBitrateController.class.getName() + ".DECREASE_LAG_MS";

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
            = LastNBitrateController.class.getName() + ".INCREASE_LAG_MS";

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
            = LastNBitrateController.class.getName() + ".INITIAL_INTERVAL_MS";

    /**
     * The <tt>Logger</tt> used by the <tt>LastNBitrateController</tt> class
     * and its instances to print debug information.
     */
    @SuppressWarnings("unused")
    private static final org.jitsi.util.Logger logger
        = org.jitsi.util.Logger.getLogger(LastNBitrateController.class);

    /**
     * The minimum bitrate in bits per second to assume for an endpoint.
     */
    private static int MIN_ASSUMED_ENDPOINT_BITRATE_BPS = 400000;

    /**
     * The name of the property which can be used to control the
     * <tt>MIN_ASSUMED_ENDPOINT_BITRATE_BPS</tt> constant.
     */
    private static final String MIN_ASSUMED_ENDPOINT_BITRATE_BPS_PNAME
            = LastNBitrateController.class.getName()
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
            = LastNBitrateController.class.getName() + ".REMB_AVERAGE_INTERVAL_MS";

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
            = LastNBitrateController.class.getName() + ".REMB_MULT_CONSTANT";

    /**
     * The {@link RecurringRunnableExecutor} which will periodically call
     * {@link #run()} on active {@link LastNBitrateController} instances.
     */
    private static RecurringRunnableExecutor recurringRunnablesExecutor;

    /**
     * Initializes the constants used by this class from the configuration.
     */
    private static void initializeConfiguration(ConfigurationService cfg)
    {
        synchronized (LastNBitrateController.class)
        {
            if (configurationInitialized)
                return;
            configurationInitialized = true;

            recurringRunnablesExecutor
                = new RecurringRunnableExecutor(
                        LastNBitrateController.class.getSimpleName());

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
     * The <tt>BitrateAdaptor</tt> to use to adapt the bandwidth.
     */
    private BitrateAdaptor bitrateAdaptor;
    
    /**
     * Whether this <tt>LastNBitrateController</tt> has set its
     * <tt>BitrateAdaptor</tt>.
     */
    private boolean bitrateAdaptorSet = false;

    /**
     * The <tt>VideoChannel</tt> of this <tt>LastNBitrateController</tt>.
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

    private final LastNController lastNController;

    /**
     * The latest estimation of the available bandwidth as reported by our
     * {@link Channel}'s {@link MediaStream}'s {@link BandwidthEstimator}.
     */
    private long latestBwe = -1;

    /**
     * The time that {@link #run()} was last called.
     */
    private long lastUpdateTime = -1;

    /**
     * Initializes a new <tt>LastNBitrateController</tt> instance.
     *
     * @param channel the <tt>VideoChannel</tt> for which the new instance is to
     * serve.
     */
    public LastNBitrateController(LastNController lastNController,
                                  VideoChannel channel)
    {
        this.channel = channel;
        this.lastNController = lastNController;

        initializeConfiguration(
                ServiceUtils.getService(
                        channel.getBundleContext(),
                        ConfigurationService.class));

        // Create a bandwidth estimator and hook us up to changes to the
        // estimation.
        BandwidthEstimator be
            = ((VideoMediaStream) channel.getStream())
                .getOrCreateBandwidthEstimator();
        be.addListener(this);

        recurringRunnablesExecutor.registerRecurringRunnable(this);
    }

    /**
     * Releases resources used by this instance and stops the periodic execution
     * of {@link #run()}.
     */
    @Override
    public void close()
    {
        recurringRunnablesExecutor.deRegisterRecurringRunnable(this);
    }

    int calcNumEndpointsThatFitIn()
    {
        final long availableBandwidth = receivedRembs.getLast();
                //= (long) (receivedRembs.getAverage(now) * REMB_MULT_CONSTANT);
        long remainingBandwidth = availableBandwidth;
        int numEndpointsThatFitIn = 0;
        Conference conference = channel.getContent().getConference();

        // Calculate the biggest number K, such that there are at least K other
        // endpoints in the conference, and the cumulative bitrate of the first
        // K endpoints does not exceed the available bandwidth estimate.
        for (String endpointId : lastNController.getForwardedEndpoints())
        {
            Endpoint endpoint = conference.getEndpoint(endpointId);

            if (endpoint != null)
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
     * Gets the <tt>VideoChannel</tt> of this <tt>LastNBitrateController</tt>.
     *
     * @return the <tt>VideoChannel</tt> of this <tt>LastNBitrateController</tt>.
     */
    VideoChannel getChannel()
    {
        return channel;
    }

    /**
     * Returns the incoming bitrate in bits per second from all
     * <tt>VideoChannel</tt>s of the endpoint <tt>endpoint</tt> or
     * {@link #MIN_ASSUMED_ENDPOINT_BITRATE_BPS} if the actual bitrate is below
     * that limit.
     *
     * @param endpoint the endpoint.
     * @return the incoming bitrate in bits per second from <tt>endpoint</tt>,
     * or {@link #MIN_ASSUMED_ENDPOINT_BITRATE_BPS} if the actual bitrate is
     * below that limit.
     */
    private long getEndpointBitrate(Endpoint endpoint)
    {
        SimulcastEngine mySM
            = this.channel.getTransformEngine().getSimulcastEngine();
        long bitrate = 0;

        for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
        {
            if (channel != null && channel instanceof VideoChannel)
            {
                VideoChannel vc = (VideoChannel) channel;
                SimulcastEngine simulcastEngine
                    = vc.getTransformEngine().getSimulcastEngine();
                if (mySM != null && simulcastEngine != null
                        && simulcastEngine.getSimulcastReceiver().isSimulcastSignaled())
                {
                    // TODO we need a more general way for this
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
     * <tt>LastNBitrateController</tt>.
     *
     * @return the <tt>BitrateAdaptor</tt> of this <tt>LastNBitrateController</tt>.
     */
    private BitrateAdaptor getOrCreateBitrateAdaptor()
    {
        if (bitrateAdaptor == null && !bitrateAdaptorSet)
        {
            bitrateAdaptorSet = true;

            if (lastNController.getAdaptiveLastN())
            {
                bitrateAdaptor = new VideoChannelLastNAdaptor(this);
            }
            else if (lastNController.getAdaptiveSimulcast())
            {
                bitrateAdaptor = new SimulcastAdaptor(this);
            }
        }

        return bitrateAdaptor;
    }

    /**
     * Notifies this instance that an RTCP REMB packet with a bitrate value of
     * <tt>remb</tt> was received on its associated <tt>VideoChannel</tt>.
     *
     * @param remb the bitrate of the REMB packet received.
     */
    @Override
    public void bandwidthEstimationChanged(long remb)
    {
        latestBwe = remb;
    }

    /**
     * {@inheritDoc}
     * @return Zero.
     */
    @Override
    public void run()
    {
        long remb = latestBwe;
        long now = System.currentTimeMillis();
        lastUpdateTime = now;

        BitrateAdaptor bitrateAdaptor = getOrCreateBitrateAdaptor();
        if (bitrateAdaptor == null)
        {
            // A bitrate adaptor is not set. It makes no sense to continue.
            return;
        }

        // The number of endpoints this channel is currently receiving
        int receivingEndpointCount
            = lastNController.getForwardedEndpoints().size();

        if (firstRemb == -1)
            firstRemb = now;

        // Update the list of received values, so that the new value is taken
        // into account in the average taken below.
        receivedRembs.add(now, remb);

        // Do not change lastN in the initial interval (give time to the
        // incoming REMBs to "ramp-up").
        if (now - firstRemb <= INITIAL_INTERVAL_MS)
        {
            return;
        }

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

        return;
    }

    LastNController getLastNController()
    {
        return lastNController;
    }

    @Override
    public long getTimeUntilNextRun()
    {
        return
                (lastUpdateTime < 0L)
                        ? 0L
                        : lastUpdateTime + PROCESS_INTERVAL_MS
                        - System.currentTimeMillis();
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
        private final Map<Long, Long> receivedRembs = new HashMap<>();

        /**
         * The sum of all values in this list.
         */
        private long sum = 0;

        private long last = -1;

        /**
         * Used in {@link #clean(long)}.
         */
        private final List<Long> toRemove = new ArrayList<>();

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
        private synchronized void add(long time, long rate)
        {
            sum += rate;
            receivedRembs.put(time, rate);
            last = rate;
            clean(time);
        }

        /**
         * Removes values added before <tt>time - period</tt>.
         */
        private synchronized void clean(long time)
        {
            // Comments from Lyubomir Marinov:
            // * toRemove is an unnecessary collection that takes up instance
            // space, grows and shrinks;
            // * toRemove retains elements even after it has lived up its
            // purpose;
            // * Because of toRemove, more auto(un)boxing happens;
            // * receivedRembs is searched upon removal.

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
        private synchronized long getAverage(long time)
        {
            clean(time);

            int size = receivedRembs.size();

            return (size == 0) ? 0 : (sum / size);
        }

        private long getLast()
        {
            return last;
        }
    }
}

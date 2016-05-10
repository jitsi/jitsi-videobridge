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
package org.jitsi.videobridge.simulcast.sendmodes;

import net.java.sip.communicator.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;
import org.jitsi.videobridge.simulcast.messages.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * The <tt>SwitchingSendMode</tt> implements the switching simulcast streams
 * mode in which the endpoint receiving the simulcast that we send it is aware
 * of all the simulcast stream SSRCs and it manages the switching at the
 * client side. The receiving endpoint is notified about changes in the
 * simulcast streams that it receives through data channel messages.
 *
 * @author George Politis
 */
@Deprecated
public class SwitchingSendMode
    extends SendMode
{
    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingStreams</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SwitchingSendMode.class);

    /**
     * Defines the default value of how many packets of the next simulcast
     * stream must be seen before switching to that stream. Also see
     * <tt>minNextSeen</tt>.
     */
    private static int MIN_NEXT_SEEN_DEFAULT = 125;

    /**
     * The name of the property which can be used to control the
     * <tt>MIN_NEXT_SEEN</tt> constant.
     */
    private static final String MIN_NEXT_SEEN_PNAME =
        SwitchingSendMode.class.getName() + ".MIN_NEXT_SEEN";

    /**
     * Helper object that <tt>SwitchingSimulcastSender</tt> instances use to
     * build JSON messages.
     */
    private static final SimulcastMessagesMapper mapper
        = new SimulcastMessagesMapper();

    /**
     * The sync root object protecting the access to the simulcast streams.
     */
    private final Object sendStreamsSyncRoot = new Object();

    /**
     * Defines how many packets of the next simulcast stream must be seen before
     * switching to that stream. This value is appropriate for the base stream
     * and needs to be adjusted for use with upper streams, if one wants to
     * achieve (approximately) the same timeout for simulcast streams of
     * different order.
     */
    private int minNextSeen = MIN_NEXT_SEEN_DEFAULT;

    /**
     * Holds the number of packets of the next simulcast stream that have been
     * seen so far.
     */
    private int seenNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that is
     * currently being received.
     */
    private WeakReference<SimulcastStream> weakCurrent;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that will be
     * (possibly) received next.
     */
    private WeakReference<SimulcastStream> weakNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastStream</tt> that overrides
     * the simulcast stream that is currently being received. Originally
     * introduced for adaptive bitrate control and the <tt>SimulcastAdaptor</tt>.
     */
    private WeakReference<SimulcastStream> weakOverride;

    /**
     * Boolean indicating whether this mode has been initialized or not.
     */
    private boolean isInitialized = false;

    /**
     * Ctor.
     *
     * @param simulcastSender
     */
    public SwitchingSendMode(SimulcastSender simulcastSender)
    {
        super(simulcastSender);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receive(SimulcastStream simStream)
    {
        SwitchingModeOptions options = new SwitchingModeOptions();

        options.setNextOrder(simStream.getOrder());
        options.setHardSwitch(true);

        configure(options);

        // Forget the next simulcast stream if it has stopped streaming.
        synchronized (sendStreamsSyncRoot)
        {
            SimulcastStream next = getNext();
            if (next != null && !next.isStreaming())
            {
                this.weakNext = null;
                this.seenNext = 0;

                nextSimulcastStreamStopped(next);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean accept(RawPacket pkt)
    {
        if (pkt == null)
        {
            return false;
        }

        this.assertInitialized();

        SimulcastStream current = getCurrent();
        boolean accept = false;

        if (current != null)
            accept = current.matches(pkt);

        if (!accept)
        {
            SimulcastStream next = getNext();

            if (next != null)
            {
                accept = next.matches(pkt);
                if (accept)
                    maybeSwitchToNext();
            }
        }

        SimulcastStream override = getOverride();

        if (override != null)
            accept = override.matches(pkt);

        if (logger.isDebugEnabled())
        {
            logger.debug("Accepting packet "
                + pkt.getSequenceNumber() + " from SSRC " + pkt.getSSRC());
        }

        return accept;
    }

    /**
     * Gets the <tt>SimulcastStream</tt> that is currently being received.
     *
     * @return
     */
    public SimulcastStream getCurrent()
    {
        WeakReference<SimulcastStream> wr = this.weakCurrent;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastStream</tt> that was previously being received.
     *
     * @return
     */
    private SimulcastStream getNext()
    {
        WeakReference<SimulcastStream> wr = this.weakNext;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastStream</tt> that overrides the simulcast stream
     * that is currently being received. Originally introduced for the
     * <tt>SimulcastAdaptor</tt>.
     *
     * @return
     */
    private SimulcastStream getOverride()
    {
        WeakReference<SimulcastStream> wr = this.weakOverride;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Initializes this mode, if it has not already been initialized.
     */
    private void assertInitialized()
    {
        if (isInitialized)
        {
            return;
        }

        isInitialized = true;

        SwitchingModeOptions options = new SwitchingModeOptions();

        // or, stream both the current and the next stream simultaneously
        // to give some time to the client decoder to resume.
        VideoChannel sendVideoChannel = getSimulcastSender()
            .getSimulcastSenderManager().getSimulcastEngine().getVideoChannel();

        ConfigurationService cfg
            = ServiceUtils.getService(sendVideoChannel.getBundleContext(),
            ConfigurationService.class);

        options.setMinNextSeen(cfg != null
            ? cfg.getInt(SwitchingSendMode.MIN_NEXT_SEEN_PNAME,
                SwitchingSendMode.MIN_NEXT_SEEN_DEFAULT)
            : SwitchingSendMode.MIN_NEXT_SEEN_DEFAULT);

        this.configure(options);
    }

    /**
     * Sets the receiving simulcast substream for the peers in the endpoints
     * parameter.
     *
     * @param options
     */
    private void configure(SwitchingModeOptions options)
    {
        if (options == null)
        {
            logger.warn("cannot configure next simulcast stream because the " +
                "parameter is null.");
            return;
        }

        Integer mns = options.getMinNextSeen();
        if (mns != null)
        {
            this.minNextSeen = mns;
        }

        // Configures the "next" simulcast stream to receive, if one is to be
        // configured.
        Integer nextOrder = options.getNextOrder();
        if (nextOrder == null)
        {
            return;
        }

        SimulcastReceiver simulcastReceiver
            = getSimulcastSender().getSimulcastReceiver();
        if (simulcastReceiver == null
                || !simulcastReceiver.isSimulcastSignaled())
        {
            logger.warn("doesn't have any simulcast streams.");
            return;
        }

        SimulcastStream next = simulcastReceiver.getSimulcastStream(nextOrder);

        // Do NOT switch to hq if it's not streaming.
        if (next == null
                || (next.getOrder()
                        != SimulcastStream.SIMULCAST_LAYER_ORDER_BASE
                    && !next.isStreaming()))
        {
            return;
        }

        SimulcastStream current = getCurrent();

        // Do NOT switch to an already receiving simulcast stream.
        if (current == next)
        {
            // and forget "previous" next, we're sticking with current.
            this.weakNext = null;
            this.seenNext = 0;
        }
        else
        {
            // If current has changed, request an FIR, notify the parent
            // endpoint and change the receiving streams.

            if (options.isHardSwitch() && next != getNext())
            {
                // XXX(gp) run these in the event dispatcher thread?

                // Send FIR requests first.
                if (getOverride() == null)
                {
                    next.askForKeyframe();
                }
            }

            if (options.isUrgent() || current == null || this.minNextSeen < 1)
            {
                // Receiving simulcast streams have brutally changed. Create
                // and send an event through data channels to the receiving
                // endpoint.
                if (getOverride() == null)
                {
                    this.simulcastStreamsChanged(next);
                }

                this.weakCurrent = new WeakReference<>(next);
                this.weakNext = null;

                // Since the currently received simulcast stream has changed,
                // reset the seenCurrent counter.
                this.seenNext = 0;
            }
            else
            {
                // Receiving simulcast streams are changing, create and send
                // an event through data channels to the receiving endpoint.
                if (getOverride() == null)
                {
                    this.simulcastStreamsChanging(next);
                }

                // If the simulcast streams we receive has changed (hasn't
                // dropped), then continue streaming the previous simulcast
                // stream for a short period of time while the client receives
                // adjusts its video.
                this.weakNext = new WeakReference<>(next);

                // Since the currently received simulcast stream has changed,
                // reset the seenCurrent counter.
                this.seenNext = 0;
            }
        }
    }

    /**
     *
     */
    private void maybeSwitchToNext()
    {
        synchronized (sendStreamsSyncRoot)
        {
            SimulcastStream next = getNext();

            // If there is a previous simulcast stream to timeout, and we have
            // received "enough" packets from the current simulcast stream,
            // expire the previous simulcast stream.
            if (next != null)
            {
                seenNext++;

                // NOTE(gp) not unexpectedly we have observed that 250 high
                // quality packets make 5 seconds to arrive (approx), then 250
                // low quality packets will make 10 seconds to arrive (approx),
                // If we don't take that fact into account, then the immediate
                // lower stream makes twice as much to expire.
                //
                // Assuming that each upper stream doubles the number of packets
                // it sends in a given interval, we normalize the MAX_NEXT_SEEN
                // to reflect the different relative rates of incoming packets
                // of the different simulcast streams we receive.

                if (seenNext > minNextSeen * Math.pow(2, next.getOrder()))
                {
                    if (getOverride() == null)
                    {
                        simulcastStreamsChanged(next);
                    }

                    weakCurrent = weakNext;
                    weakNext = null;
                }
            }
        }
    }

    /**
     *
     * @param simStream
     */
    private void nextSimulcastStreamStopped(SimulcastStream simStream)
    {
        if (simStream == null)
        {
            logger.warn("Requested to send a next simulcast stream stopped " +
                "event but simStream is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSimulcastSender().getReceiveEndpoint()) != null
                && (peer = getSimulcastSender().getSendEndpoint()) != null)
        {
            logger.debug("Sending a next simulcast stream stopped event to "
                + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastStreamsChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast streams changed, create and send
            // an event through data channels to the receiving endpoint.
            NextSimulcastStreamStoppedEvent ev
                = new NextSimulcastStreamStoppedEvent();

            ev.endpointSimulcastStreams = new EndpointSimulcastStream[]{
                new EndpointSimulcastStream(peer.getID(), simStream)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                    "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast streams changed event " +
                "because self == null || peer == null " +
                "|| current == null");
        }
    }

    /**
     *
     * @param simStream
     */
    private void simulcastStreamsChanged(SimulcastStream simStream)
    {
        if (simStream == null)
        {
            logger.warn("Requested to send a simulcast streams changed event" +
                    "but simStream is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSimulcastSender().getReceiveEndpoint()) != null
                && (peer = getSimulcastSender().getSendEndpoint()) != null)
        {
            logger.debug("Sending a simulcast streams changed event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastStreamsChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast streams changed, create and send
            // an event through data channels to the receiving endpoint.
            SimulcastStreamsChangedEvent ev
                    = new SimulcastStreamsChangedEvent();

            ev.endpointSimulcastStreams = new EndpointSimulcastStream[]{
                    new EndpointSimulcastStream(peer.getID(), simStream)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast streams changed event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }

    }

    /**
     *
     * @param simStream
     */
    private void simulcastStreamsChanging(SimulcastStream simStream)
    {
        if (simStream == null)
        {
            logger.warn("Requested to send a simulcast streams changing event" +
                    "but simStream is null!");
            return;
        }

        Endpoint self
            = getSimulcastSender().getReceiveEndpoint();
        Endpoint peer = getSimulcastSender().getSendEndpoint();

        if (self != null && peer  != null)
        {
            logger.debug("Sending a simulcast streams changing event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastStreamsChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast streams changed, create and send
            // an event through data channels to the receiving
            // endpoint.
            SimulcastStreamsChangingEvent ev
                    = new SimulcastStreamsChangingEvent();

            ev.endpointSimulcastStreams = new EndpointSimulcastStream[]{
                    new EndpointSimulcastStream(peer.getID(), simStream)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast streams changing event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }
    }

    /**
     *
     * @param options
     */
    private void maybeConfigureOverride(SwitchingModeOptions options)
    {
        if (options == null)
        {
            return;
        }

        Integer overrideOrder = 1; // options.getOverrideOrder();
        if (overrideOrder == null)
        {
            return;
        }

        SimulcastReceiver simulcastReceiver
            = this.getSimulcastSender().getSimulcastReceiver();

        if (simulcastReceiver == null
                || !simulcastReceiver.isSimulcastSignaled())
        {
            return;
        }

        if (overrideOrder
                == SimulcastSenderManager.SIMULCAST_LAYER_ORDER_NO_OVERRIDE)
        {
            synchronized (sendStreamsSyncRoot)
            {
                this.weakOverride = null;
                SimulcastStream current = getCurrent();
                if (current != null)
                {
                    current.askForKeyframe();
                    this.simulcastStreamsChanged(current);
                }
            }
        }
        else
        {
            SimulcastStream override
                = simulcastReceiver.getSimulcastStream(overrideOrder);
            if (override != null)
            {
                synchronized (sendStreamsSyncRoot)
                {
                    this.weakOverride = new WeakReference<>(override);
                    override.askForKeyframe();
                    this.simulcastStreamsChanged(override);
                }
            }
        }
    }

    /**
     * Holds the configuration options for the <tt>SwitchingSimulcastSender</tt>.
     *
     * @author George Politis
     */
    static class SwitchingModeOptions
    {
        /**
         *
         */
        private Integer nextOrder;

        /**
         *
         */
        private Integer minNextSeen;

        /**
         * A switch that is urgent (e.g. because of a simulcast stream drop).
         */
        private boolean urgent;

        /**
         * A switch that requires a key frame.
         */
        private boolean hardSwitch;

        /**
         *
         * @return
         */
        public Integer getMinNextSeen()
        {
            return minNextSeen;
        }

        /**
         *
         * @param minNextSeen
         */
        public void setMinNextSeen(Integer minNextSeen)
        {
            this.minNextSeen = minNextSeen;
        }

        /**
         *
         * @param urgent
         */
        public void setUrgent(boolean urgent)
        {
            this.urgent = urgent;
        }

        /**
         *
         * @param nextOrder
         */
        public void setNextOrder(Integer nextOrder)
        {
            this.nextOrder = nextOrder;
        }

        /**
         *
         * @param hardSwitch
         */
        public void setHardSwitch(boolean hardSwitch)
        {
            this.hardSwitch = hardSwitch;
        }

        /**
         *
         * @return
         */
        public Integer getNextOrder()
        {
            return nextOrder;
        }

        /**
         *
         * @return
         */
        public boolean isHardSwitch()
        {
            return hardSwitch;
        }

        /**
         *
         * @return
         */
        public boolean isUrgent()
        {
            return urgent;
        }
    }
}

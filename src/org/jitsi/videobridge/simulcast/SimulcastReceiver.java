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
package org.jitsi.videobridge.simulcast;

import java.beans.*;
import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import net.java.sip.communicator.util.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.messages.*;

/**
 * @author George Politis
 */
class SimulcastReceiver
    implements PropertyChangeListener
{
    /**
     * The <tt>SimulcastReceiverOptions</tt> to use when creating a new
     * <tt>SimulcastReceiver</tt>.
     */
    protected static final SimulcastReceiverOptions initOptions;

    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(SimulcastReceiver.class);

    /**
     * Helper object that <tt>SimulcastReceiver</tt> instances use to build
     * JSON messages.
     */
    private final static SimulcastMessagesMapper mapper
        = new SimulcastMessagesMapper();

    /**
     * Defines how many packets of the next layer must be seen before switching
     * to that layer. This value is appropriate for the base layer and needs to
     * be adjusted for use with upper layers, if one wants to achieve
     * (approximately) the same timeout for layers of different order.
     */
    private static int MAX_NEXT_SEEN = 125;

    /**
     * The name of the property which can be used to control the
     * <tt>MAX_NEXT_SEEN</tt> constant.
     */
    private static final String MAX_NEXT_SEEN_PNAME =
        SimulcastReceiver.class.getName() + ".MAX_NEXT_SEEN";

    static
    {
        // Static initialization is performed once per class-loader. So, this
        // method can be considered thread safe for our purposes.

        initOptions = new SimulcastReceiverOptions();
        initOptions.setNextOrder(SimulcastManager.SIMULCAST_LAYER_ORDER_LQ);
        // options.setUrgent(false);
        // options.setHardSwitch(false);
    }

    /**
     * The <tt>SimulcastManager</tt> of the parent endpoint.
     */
    private final SimulcastManager mySM;

    /**
     * The sync root object for synchronizing access to the receive layers.
     */
    private final Object receiveLayersSyncRoot = new Object();

    /**
     * Holds the number of packets of the next layer have been seen so far.
     */
    private int seenNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that is
     * currently being received.
     */
    private WeakReference<SimulcastLayer> weakCurrent;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that will be
     * (possibly) received next.
     */
    private WeakReference<SimulcastLayer> weakNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that overrides
     * the layer that is currently being received.
     */
    private WeakReference<SimulcastLayer> weakOverride;

    /**
     * The <tt>SimulcastManager</tt> of the peer endpoint.
     */
    private final WeakReference<SimulcastManager> weakPeerSM;

    /**
     * The <tt>PropertyChangeListener</tt> implementation employed by this
     * instance to listen to changes in the values of properties of interest to
     * this instance. For example, listens to <tt>Conference</tt> in order to
     * notify about changes in the list of <tt>Endpoint</tt>s participating in
     * the multipoint conference. The implementation keeps a
     * <tt>WeakReference</tt> to this instance and automatically removes itself
     * from <tt>PropertyChangeNotifier</tt>s.
     */
    private final PropertyChangeListener weakPropertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

    /**
     * Whether the values for the constants have been initialized or not.
     */
    private static boolean configurationInitialized = false;

    /**
     * Ctor.
     *
     * @param mySM
     * @param peerSM
     */
    public SimulcastReceiver(SimulcastManager mySM, SimulcastManager peerSM)
    {
        this.weakPeerSM = new WeakReference<SimulcastManager>(peerSM);
        this.mySM = mySM;

        this.initializeConfiguration();

        // Listen for property changes.
        peerSM.addPropertyChangeListener(weakPropertyChangeListener);
        onPeerLayersChanged(peerSM);

        mySM.getVideoChannel()
                .addPropertyChangeListener(weakPropertyChangeListener);

        Endpoint self = getSelf();
        onEndpointChanged(self, null);
    }

    private void initializeConfiguration()
    {
        synchronized (SimulcastReceiver.class)
        {
            if (configurationInitialized)
            {
                return;
            }

            configurationInitialized = true;

            VideoChannel channel = this.mySM.getVideoChannel();
            ConfigurationService cfg
                = ServiceUtils.getService(
                channel.getBundleContext(),
                ConfigurationService.class);

            if (cfg != null)
            {
                MAX_NEXT_SEEN = cfg.getInt(MAX_NEXT_SEEN_PNAME, MAX_NEXT_SEEN);
            }
        }
    }

    /**
     *
     * @param ssrc
     * @return
     */
    public boolean accept(long ssrc)
    {
        SimulcastLayer current = getCurrent();
        boolean accept = false;

        if (current != null)
            accept = current.accept(ssrc);

        if (!accept)
        {
            SimulcastLayer next = getNext();

            if (next != null)
            {
                accept = next.accept(ssrc);
                if (accept)
                    maybeSwitchToNext();
            }
        }

        SimulcastLayer override = getOverride();

        if (override != null)
            accept = override.accept(ssrc);

        if (!accept)
        {
            // For SRTP replay protection the webrtc.org implementation uses a
            // replay database with extended range, using a rollover counter
            // (ROC) which counts the number of times the RTP sequence number
            // carried in the RTP packet has rolled over.
            //
            // In this way, the ROC extends the 16-bit RTP sequence number to a
            // 48-bit "SRTP packet index". The ROC is not be explicitly
            // exchanged between the SRTP endpoints because in all practical
            // situations a rollover of the RTP sequence number can be detected
            // unless 2^15 consecutive RTP packets are lost.
            //
            // For every 0x800 (2048) dropped packets (at most), send 8 packets
            // so that the receiving endpoint can update its ROC.
            //
            // TODO(gp) We may want to move this code somewhere more centralized
            // to take into account last-n etc.

            Integer key = Integer.valueOf((int) ssrc);
            CyclicCounter counter = dropped.getOrCreate(key, 0x800);
            accept = counter.cyclicallyIncrementAndGet() < 8;
        }

        return accept;
    }


    static class CyclicCounter {

        private final int maxVal;
        private final AtomicInteger ai = new AtomicInteger(0);

        public CyclicCounter(int maxVal) {
            this.maxVal = maxVal;
        }

        public int cyclicallyIncrementAndGet() {
            int curVal, newVal;
            do {
                curVal = this.ai.get();
                newVal = (curVal + 1) % this.maxVal;
                // note that this doesn't guarantee fairness
            } while (!this.ai.compareAndSet(curVal, newVal));
            return newVal;
        }

    }

    /**
     * Multitone pattern with Lazy Initialization.
     */
    static class CyclicCounters
    {
        private final Map<Integer, CyclicCounter> instances
            = new ConcurrentHashMap<Integer, CyclicCounter>();
        private Lock createLock = new ReentrantLock();

        CyclicCounter getOrCreate(Integer key, int maxVal) {
            CyclicCounter instance = instances.get(key);
            if (instance == null) {
                createLock.lock();
                try {
                    if (instance == null) {
                        instance = new CyclicCounter(maxVal);
                        instances.put(key, instance);
                    }
                } finally {
                    createLock.unlock();
                }
            }
            return instance;
        }
    }

    private final CyclicCounters dropped = new CyclicCounters();

    private void askForKeyframe(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested a key frame for null layer!");
            return;
        }

        SimulcastManager peerSM = getPeerSM();
        if (peerSM == null)
        {
            logger.warn("Requested a key frame but the peer simulcast " +
                    "manager is null!");
            return;
        }

        peerSM.getVideoChannel().askForKeyframes(
                new int[]{(int) layer.getPrimarySSRC()});

        if (logger.isDebugEnabled())
        {
            Map<String, Object> map = new HashMap<String, Object>(3);
            map.put("self", getSelf());
            map.put("peer", getPeer());
            map.put("layer", layer);
            StringCompiler sc = new StringCompiler(map);

            logger.debug(sc.c("The simulcast receiver of {self.id} for " +
                    "{peer.id} has asked for a key frame for layer " +
                    "{layer.order} ({layer.primarySSRC})."));
        }
    }

    /**
     * Sets the receiving simulcast substream for the peers in the endpoints
     * parameter.
     *
     * @param options
     */
    protected void configure(SimulcastReceiverOptions options)
    {
        synchronized (receiveLayersSyncRoot)
        {
            this.maybeConfigureOverride(options);
            this.maybeConfigureNext(options);
        }
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that is currently being received.
     *
     * @return
     */
    private SimulcastLayer getCurrent()
    {
        WeakReference<SimulcastLayer> wr = this.weakCurrent;
        return (wr != null) ? wr.get() : null;
    }

    public long getIncomingBitrate(boolean noOverride)
    {
        long bitrate = 0;

        if (!noOverride)
        {
            synchronized (receiveLayersSyncRoot)
            {
                SimulcastLayer override = getOverride();
                if (override != null)
                {
                    bitrate = override.getBitrate();
                }
                else
                {
                    SimulcastLayer current = getCurrent();
                    if (current != null)
                    {
                        bitrate = current.getBitrate();
                    }
                }
            }
        }
        else
        {
            SimulcastLayer current = getCurrent();
            if (current != null)
            {
                bitrate = current.getBitrate();
            }
        }

        return bitrate;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that was previously being received.
     *
     * @return
     */
    private SimulcastLayer getNext()
    {
        WeakReference<SimulcastLayer> wr = this.weakNext;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that overrides the layer that is
     * currently being received.
     *
     * @return
     */
    private SimulcastLayer getOverride()
    {
        WeakReference<SimulcastLayer> wr = this.weakOverride;
        return (wr != null) ? wr.get() : null;
    }

    private Endpoint getPeer()
    {
        // TODO(gp) maybe add expired checks (?)
        SimulcastManager sm;
        VideoChannel vc;
        Endpoint peer;

        peer = ((sm = getPeerSM()) != null && (vc = sm.getVideoChannel()) != null)
                ? vc.getEndpoint() : null;

        if (peer == null)
        {
            logger.warn("Peer is null!");

            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        return peer;
    }

    private SimulcastManager getPeerSM()
    {
        WeakReference<SimulcastManager> wr = this.weakPeerSM;
        SimulcastManager peerSM = (wr != null) ? wr.get() : null;

        if (peerSM == null)
        {
            logger.warn("The peer simulcast manager is null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        Arrays.toString(
                                Thread.currentThread().getStackTrace()));
            }
        }

        return peerSM;
    }

    private Endpoint getSelf()
    {
        // TODO(gp) maybe add expired checks (?)
        SimulcastManager sm = this.mySM;
        VideoChannel vc;
        Endpoint self;

        self = (sm != null && (vc = sm.getVideoChannel()) != null)
                ? vc.getEndpoint() : null;

        if (self == null)
        {
            logger.warn("Self is null!");

            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        return self;
    }

    private void maybeConfigureNext(SimulcastReceiverOptions options)
    {
        if (options == null)
        {
            if (logger.isWarnEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("self", getSelf());
                StringCompiler sc = new StringCompiler(map);

                logger.warn(sc.c("{self.id} cannot configure next simulcast " +
                        "layer because the parameter is null."));
            }

            return;
        }

        Integer nextOrder = options.getNextOrder();
        if (nextOrder == null)
        {
            return;
        }
        SimulcastManager peerSM = this.getPeerSM();

        if (peerSM == null || !peerSM.hasLayers())
        {
            if (logger.isWarnEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("peer", getPeer());
                StringCompiler sc = new StringCompiler(map);

                logger.warn(sc.c("{peer.id} doesn't have any simulcast layers."));
            }

            return;
        }

        SimulcastLayer next
                = peerSM.getSimulcastLayer(options.getNextOrder());

        // Do NOT switch to hq if it's not streaming.
        if (next == null
                || (next.getOrder()
                != SimulcastManager.SIMULCAST_LAYER_ORDER_LQ
                && !next.isStreaming()))
        {
            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("self", getSelf());
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("{self.id} ignoring request to switch to " +
                        "higher order layer because it is not currently " +
                        "being streamed."));
            }
            return;
        }

        synchronized (receiveLayersSyncRoot)
        {
            SimulcastLayer current = getCurrent();

            // Do NOT switch to an already receiving layer.
            if (current == next)
            {
                // and forget "previous" next, we're sticking with current.
                this.weakNext = null;
                this.seenNext = 0;

                if (logger.isDebugEnabled())
                {
                    Map<String, Object> map = new HashMap<String, Object>(4);
                    map.put("self", getSelf());
                    map.put("peer", getPeer());
                    map.put("current", current);
                    map.put("next", next);
                    StringCompiler sc = new StringCompiler(map);

                    logger.debug(sc.c("The simulcast receiver of {self.id} for " +
                            "{peer.id} already receives layer {next.order} " +
                            "({next.primarySSRC})."));
                }

                return;
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
                        this.askForKeyframe(next);
                    }
                    else
                    {
                        if (logger.isDebugEnabled())
                        {
                            Map<String, Object> map
                                    = new HashMap<String, Object>(2);
                            map.put("self", getSelf());
                            map.put("peer", getPeer());
                            StringCompiler sc = new StringCompiler(map);
                            logger.debug(sc.c("The simulcast receiver of " +
                                    "{self.id} for {peer.id} skipped a key " +
                                    "frame request because an override is " +
                                    "set."));
                        }
                    }
                }


                if (options.isUrgent() || current == null || MAX_NEXT_SEEN < 1)
                {
                    // Receiving simulcast layers have brutally changed. Create
                    // and send an event through data channels to the receiving
                    // endpoint.
                    if (getOverride() == null)
                    {
                        this.sendSimulcastLayersChangedEvent(next);
                    }
                    else
                    {
                        Map<String, Object> map = new HashMap<String, Object>(2);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        StringCompiler sc = new StringCompiler(map);
                        logger.debug(sc.c("The simulcast receiver of " +
                                "{self.id} for {peer.id} skipped a " +
                                "changed event because an override is " +
                                "set."));
                    }

                    this.weakCurrent = new WeakReference<SimulcastLayer>(next);
                    this.weakNext = null;

                    // Since the currently received layer has changed, reset the
                    // seenCurrent counter.
                    this.seenNext = 0;

                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(4);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        map.put("next", next);
                        map.put("urgently", options.isUrgent()
                                ? "urgently" : "");
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("The simulcast receiver " +
                                "of {self.id} for {peer.id} has {urgently} " +
                                "switched to layer {next.order} " +
                                "({next.primarySSRC}).").toString()
                                .replaceAll("\\s+", " "));
                    }
                }
                else
                {
                    // Receiving simulcast layers are changing, create and send
                    // an event through data channels to the receiving endpoint.
                    if (getOverride() == null)
                    {
                        this.sendSimulcastLayersChangingEvent(next);
                    }
                    else
                    {
                        Map<String, Object> map = new HashMap<String, Object>(2);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        StringCompiler sc = new StringCompiler(map);
                        logger.debug(sc.c("The simulcast receiver of " +
                                "{self.id} for {peer.id} skipped a " +
                                "changing event because an override is " +
                                "set."));
                    }

                    // If the layer we receive has changed (hasn't dropped),
                    // then continue streaming the previous layer for a short
                    // period of time while the client receives adjusts its
                    // video.
                    this.weakNext = new WeakReference<SimulcastLayer>(next);

                    // Since the currently received layer has changed, reset the
                    // seenCurrent counter.
                    this.seenNext = 0;

                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(3);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        map.put("next", next);
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("The simulcast receiver of " +
                                "{self.id} for {peer.id} is going to switch " +
                                "to layer {next.order} ({next.primarySSRC}) " +
                                "in a few moments.."));
                    }
                }
            }
        }
    }

    private void maybeConfigureOverride(SimulcastReceiverOptions options)
    {
        if (options == null)
        {
            if (logger.isWarnEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("self", getSelf());
                StringCompiler sc = new StringCompiler(map);

                logger.warn(sc.c("{self.id} cannot configure override" +
                        " simulcast layer because the parameter is null."));
            }

            return;
        }

        Integer overrideOrder = options.getOverrideOrder();
        if (overrideOrder == null)
        {
            return;
        }

        SimulcastManager peerSM = this.getPeerSM();

        if (peerSM == null || !peerSM.hasLayers())
        {
            if (logger.isWarnEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(1);
                map.put("peer", getPeer());
                StringCompiler sc = new StringCompiler(map);

                logger.warn(sc.c("{peer.id} doesn't have any simulcast layers."));
            }

            return;
        }

        if (overrideOrder == SimulcastManager.SIMULCAST_LAYER_ORDER_NO_OVERRIDE)
        {
            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(2);
                map.put("self", getSelf());
                map.put("peer", getPeer());
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("The simulcast receiver of {self.id} " +
                        "for {peer.id} is no longer overriding the " +
                        "receiving layer."));
            }

            synchronized (receiveLayersSyncRoot)
            {
                this.weakOverride = null;
                SimulcastLayer current = getCurrent();
                if (current != null)
                {
                    this.askForKeyframe(current);
                    this.sendSimulcastLayersChangedEvent(current);
                }
            }
        }
        else
        {
            if (peerSM != null)
            {
                SimulcastLayer override = peerSM.getSimulcastLayer(overrideOrder);
                if (override != null)
                {
                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(3);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        map.put("override", override);
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("The simulcast receiver of " +
                                "{self.id} for {peer.id} is now configured " +
                                "to override the receiving layer with the " +
                                "{override.order}-order layer " +
                                "{override.primarySSRC}."));
                    }

                    synchronized (receiveLayersSyncRoot)
                    {
                        this.weakOverride
                                = new WeakReference<SimulcastLayer>(override);
                        this.askForKeyframe(override);
                        this.sendSimulcastLayersChangedEvent(override);
                    }
                }
            }

        }
    }

    private void maybeForgetNext()
    {
        synchronized (receiveLayersSyncRoot)
        {
            SimulcastLayer next = getNext();
            if (next != null && !next.isStreaming())
            {
                this.weakNext = null;
                this.seenNext = 0;
                sendNextSimulcastLayerStoppedEvent(next);
            }
        }
    }

    /**
     * Configures this <tt>SimulcastReceiver</tt> to receive the high quality
     * stream from the associated sender.
     */
    private void receiveHigh()
    {
        SimulcastReceiverOptions options = new SimulcastReceiverOptions();

        options.setNextOrder(SimulcastManager.SIMULCAST_LAYER_ORDER_HQ);
        options.setHardSwitch(true);
        // options.setUrgent(false);

        configure(options);
    }

    /**
     * Configures this <tt>SimulcastReceiver</tt> to receive the low quality
     * stream from the associated sender.
     */
    private void receiveLow()
    {
        SimulcastReceiverOptions options = new SimulcastReceiverOptions();

        options.setNextOrder(SimulcastManager.SIMULCAST_LAYER_ORDER_LQ);
        options.setHardSwitch(true);
        // options.setUrgent(false);

        configure(options);
    }

    /**
     * Maybe send a data channel command to the associated simulcast sender to
     * make it start streaming its hq stream, if it's being watched by some receiver.
     */
    private void maybeSendStartHighQualityStreamCommand()
    {
        Endpoint newEndpoint = getPeer();
        SortedSet<SimulcastLayer> newSimulcastLayers = null;

        SimulcastManager peerSM = getPeerSM();
        if (peerSM != null)
        {
            newSimulcastLayers = peerSM.getSimulcastLayers();
        }

        SctpConnection sctpConnection;
        if (newSimulcastLayers != null
                && newSimulcastLayers.size() > 1
                /* newEndpoint != null is implied */
                && (sctpConnection = newEndpoint.getSctpConnection()) != null
                && sctpConnection.isReady()
                && !sctpConnection.isExpired())
        {
            // we have a new endpoint and it has an SCTP connection that is
            // ready and not expired. if somebody else is watching the new
            // endpoint, start its hq stream.

            boolean startHighQualityStream = false;

            for (Endpoint e
                    : mySM.getVideoChannel().getContent().getConference()
                        .getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the
                // selected endpoint changes while we're in the loop?

                if (e == newEndpoint)
                    continue;

                Endpoint eSelectedEndpoint = e.getEffectivelySelectedEndpoint();

                if (newEndpoint == eSelectedEndpoint
                        || (SimulcastManager.SIMULCAST_LAYER_ORDER_INIT > SimulcastManager.SIMULCAST_LAYER_ORDER_LQ
                                && eSelectedEndpoint == null))
                {
                    // somebody is watching the new endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, start
                    // the hq stream.

                    if (logger.isDebugEnabled())
                    {
                        Map<String,Object> map = new HashMap<String,Object>(3);

                        map.put("e", e);
                        map.put("newEndpoint", newEndpoint);
                        map.put("maybe", eSelectedEndpoint == null ? "(maybe) "
                                    : "");

                        StringCompiler sc
                            = new StringCompiler(map)
                                .c("{e.id} is {maybe} watching {newEndpoint.id}.");

                        logger.debug(
                                sc.toString().replaceAll("\\s+", " "));
                    }

                    startHighQualityStream = true;
                    break;
                }
            }

            if (startHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.debug(
                        mySM.getVideoChannel().getEndpoint().getID()
                            + " notifies " + newEndpoint.getID()
                            + " to start its HQ stream.");

                SimulcastLayer hqLayer = newSimulcastLayers.last();
                StartSimulcastLayerCommand command
                    = new StartSimulcastLayerCommand(hqLayer);
                String json = mapper.toJson(command);

                try
                {
                    newEndpoint.sendMessageOnDataChannel(json);
                }
                catch (IOException e)
                {
                    logger.error(
                            newEndpoint.getID()
                                + " failed to send message on data channel.",
                            e);
                }
            }
        }
    }

    /**
     * Maybe send a data channel command to he associated simulcast sender to
     * make it stop streaming its hq stream, if it's not being watched by any
     * receiver.
     */
    private void maybeSendStopHighQualityStreamCommand()
    {
        Endpoint oldEndpoint = getPeer();
        SortedSet<SimulcastLayer> oldSimulcastLayers = null;

        SimulcastManager peerSM = getPeerSM();
        if (peerSM != null)
        {
            oldSimulcastLayers = peerSM.getSimulcastLayers();
        }

        SctpConnection sctpConnection;
        if (oldSimulcastLayers != null
                && oldSimulcastLayers.size() > 1
                /* oldEndpoint != null is implied*/
                && (sctpConnection = oldEndpoint.getSctpConnection()) != null
                && sctpConnection.isReady()
                && !sctpConnection.isExpired())
        {
            // we have an old endpoint and it has an SCTP connection that is
            // ready and not expired. if nobody else is watching the old
            // endpoint, stop its hq stream.

            boolean stopHighQualityStream = true;
            for (Endpoint e : this.mySM.getVideoChannel()
                    .getContent().getConference().getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the selected
                // endpoint changes while we're in the loop?

                if (oldEndpoint != e
                        && (oldEndpoint == e.getEffectivelySelectedEndpoint())
                        || e.getEffectivelySelectedEndpoint() == null)
                {
                    // somebody is watching the old endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, don't
                    // stop the hq stream.
                    stopHighQualityStream = false;
                    break;
                }
            }

            if (stopHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.debug(this.mySM.getVideoChannel().getEndpoint().getID() +
                        " notifies " + oldEndpoint.getID() + " to stop " +
                        "its HQ stream.");

                SimulcastLayer hqLayer = oldSimulcastLayers.last();

                StopSimulcastLayerCommand command
                        = new StopSimulcastLayerCommand(hqLayer);

                String json = mapper.toJson(command);

                try
                {
                    oldEndpoint.sendMessageOnDataChannel(json);
                }
                catch (IOException e1)
                {
                    logger.error(oldEndpoint.getID() + " failed to send " +
                            "message on data channel.", e1);
                }
            }
        }
    }

    /**
     *
     */
    private void maybeSwitchToNext()
    {
        synchronized (receiveLayersSyncRoot)
        {
            SimulcastLayer next = getNext();

            // If there is a previous layer to timeout, and we have received
            // "enough" packets from the current layer, expire the previous
            // layer.
            if (next != null)
            {
                seenNext++;

                // NOTE(gp) not unexpectedly we have observed that 250 high
                // quality packets make 5 seconds to arrive (approx), then 250
                // low quality packets will make 10 seconds to arrive (approx),
                // If we don't take that fact into account, then the immediate
                // lower layer makes twice as much to expire.
                //
                // Assuming that each upper layer doubles the number of packets
                // it sends in a given interval, we normalize the MAX_NEXT_SEEN
                // to reflect the different relative rates of incoming packets
                // of the different simulcast layers we receive.

                if (seenNext > MAX_NEXT_SEEN * Math.pow(2, next.getOrder()))
                {
                    if (getOverride() == null)
                    {
                        this.sendSimulcastLayersChangedEvent(next);
                    }
                    else
                    {
                        if (logger.isDebugEnabled())
                        {
                            Map<String, Object> map = new HashMap<String, Object>(2);
                            map.put("self", getSelf());
                            map.put("peer", getPeer());
                            StringCompiler sc = new StringCompiler(map);
                            logger.debug(sc.c("The simulcast receiver of " +
                                    "{self.id} for {peer.id} skipped a " +
                                    "changed event because an override is " +
                                    "set."));
                        }
                    }

                    this.weakCurrent = weakNext;
                    this.weakNext = null;

                    if (logger.isDebugEnabled())
                    {
                        Map<String, Object> map = new HashMap<String, Object>(3);
                        map.put("self", getSelf());
                        map.put("peer", getPeer());
                        map.put("next", next);
                        StringCompiler sc = new StringCompiler(map);

                        logger.debug(sc.c("The simulcast receiver of " +
                                "{self.id} for {peer.id} has now switched to " +
                                "the next layer of order {next.order} " +
                                "({next.primarySSRC})."));
                    }
                }
            }
        }
    }

    private void onEndpointChanged(Endpoint newValue, Endpoint oldValue)
    {
        if (newValue != null)
        {
            newValue.addPropertyChangeListener(weakPropertyChangeListener);
        }
        else
        {
            logger.warn("Cannot listen on self, it's null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        if (oldValue != null)
        {
            oldValue.removePropertyChangeListener(weakPropertyChangeListener);
        }
    }

    private void onPeerLayerChanged(SimulcastLayer layer)
    {
        if (!layer.isStreaming())
        {
            // HQ stream has stopped, switch to a lower quality stream.

            SimulcastReceiverOptions options = new SimulcastReceiverOptions();

            options.setNextOrder(SimulcastManager.SIMULCAST_LAYER_ORDER_LQ);
            options.setHardSwitch(true);
            options.setUrgent(true);

            configure(options);

            maybeForgetNext();
        }
        else
        {
            Endpoint self = getSelf();
            Endpoint peer = getPeer();

            if (peer != null && self != null &&
                        peer == self.getEffectivelySelectedEndpoint())
            {
                SimulcastReceiverOptions options
                        = new SimulcastReceiverOptions();

                options.setNextOrder(
                        SimulcastManager.SIMULCAST_LAYER_ORDER_HQ);
                // options.setHardSwitch(false);
                // options.setUrgent(false);

                configure(options);
            }
        }
    }

    private void onPeerLayersChanged(SimulcastManager peerSM)
    {
        if (peerSM != null && peerSM.hasLayers())
        {
            for (SimulcastLayer layer : peerSM.getSimulcastLayers())
            {
                // Add listener from the current receiving simulcast layers.
                layer.addPropertyChangeListener(weakPropertyChangeListener);
            }

            // normally getPeer() == peerSM.getVideoChannel().getEndpoint()
            // holds.

            // Initialize the receiver.
            configure(initOptions);

            if (logger.isDebugEnabled())
            {
                Map<String, Object> map = new HashMap<String, Object>(2);
                map.put("self", getSelf());
                map.put("peer", getPeer());
                StringCompiler sc = new StringCompiler(map);

                logger.debug(sc.c("{self.id} listens on layer changes from " +
                        "{peer.id}."));
            }
        }
    }

    private void onSelectedEndpointChanged(
            Endpoint oldEndpoint, Endpoint newEndpoint)
    {
        synchronized (receiveLayersSyncRoot)
        {
            // Rule 1: send an hq stream only for the selected endpoint.
            if (newEndpoint == getPeer())
            {
                this.receiveHigh();

                // Rule 1.1: if the new endpoint is being watched by any of the
                // receivers, the bridge tells it to start streaming its hq
                // stream.
                this.maybeSendStartHighQualityStreamCommand();
            }

            // Rule 2: send an lq stream only for the previously selected
            // endpoint.
            if (oldEndpoint == getPeer())
            {
                this.receiveLow();
                // Rule 2.1: if the old endpoint is not being watched by any of
                // the receivers, the bridge tells it to stop streaming its hq
                // stream.
                this.maybeSendStopHighQualityStreamCommand();
            }
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent propertyChangeEvent)
    {
        if (SimulcastLayer.IS_STREAMING_PROPERTY
                .equals(propertyChangeEvent.getPropertyName()))
        {
            // A remote simulcast layer has either started or stopped
            // streaming. Deal with it.
            SimulcastLayer layer
                    = (SimulcastLayer) propertyChangeEvent.getSource();

            onPeerLayerChanged(layer);
        }
        else if (Endpoint
                .SELECTED_ENDPOINT_PROPERTY_NAME.equals(
                        propertyChangeEvent.getPropertyName()) ||
                Endpoint
                .PINNED_ENDPOINT_PROPERTY_NAME.equals(
                        propertyChangeEvent.getPropertyName()))
        {
            // endpoint == this.manager.getVideoChannel().getEndpoint() is
            // implied.

            Endpoint oldValue = (Endpoint) propertyChangeEvent.getOldValue();
            Endpoint newValue = (Endpoint) propertyChangeEvent.getNewValue();

            onSelectedEndpointChanged(oldValue, newValue);
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(
                propertyChangeEvent.getPropertyName()))
        {
            // Listen for property changes from self.
            Endpoint newValue = (Endpoint) propertyChangeEvent.getNewValue();
            Endpoint oldValue = (Endpoint) propertyChangeEvent.getOldValue();

            onEndpointChanged(newValue, oldValue);
        }
        else if (SimulcastManager.SIMULCAST_LAYERS_PROPERTY.equals(
                propertyChangeEvent.getPropertyName()))
        {
            // The simulcast layers of the peer have changed, (re)attach.
            SimulcastManager peerSM
                    = (SimulcastManager) propertyChangeEvent.getSource();

            onPeerLayersChanged(peerSM);
        }
    }

    private void sendNextSimulcastLayerStoppedEvent(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a next simulcast layer stopped " +
                "event but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a next simulcast layer stopped event to "
                + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving endpoint.
            NextSimulcastLayerStoppedEvent ev
                = new NextSimulcastLayerStoppedEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                new EndpointSimulcastLayer(peer.getID(), layer)
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
            logger.warn("Didn't send simulcast layers changed event " +
                "because self == null || peer == null " +
                "|| current == null");
        }
    }

    private void sendSimulcastLayersChangedEvent(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changed event" +
                    "but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a simulcast layers changed event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving endpoint.
            SimulcastLayersChangedEvent ev
                    = new SimulcastLayersChangedEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
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
            logger.warn("Didn't send simulcast layers changed event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }

    }

    private void sendSimulcastLayersChangingEvent(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changing event" +
                    "but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a simulcast layers changing event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving
            // endpoint.
            SimulcastLayersChangingEvent ev
                    = new SimulcastLayersChangingEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
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
            logger.warn("Didn't send simulcast layers changing event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }
    }
}

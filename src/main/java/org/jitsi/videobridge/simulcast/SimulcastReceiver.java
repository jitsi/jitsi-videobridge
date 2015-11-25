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

import org.jitsi.impl.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.messages.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The <tt>SimulcastReceiver</tt> of a <tt>SimulcastEngine</tt> receives the
 * simulcast streams from a simulcast enabled participant and manages 1 or more
 * <tt>SimulcastLayer</tt>s. It fires a property change event whenever the
 * simulcast layers that it manages change.
 *
 * This class is thread safe.
 *
 * @author George Politis
 * @author Lyubomir Marinov
 */
public class SimulcastReceiver
        extends PropertyChangeNotifier
{
    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastReceiver.class);

    /**
     * The name of the property that gets fired when there's a change in the
     * simulcast layers that this receiver manages.
     */
    public static final String SIMULCAST_LAYERS_PNAME
            = SimulcastReceiver.class.getName() + ".simulcastLayers";

    /**
     * The number of (video) frames which defines the interval of time
     * (indirectly) during which a {@code SimulcastLayer} needs to receive data
     * from its remote peer or it will be declared paused/stopped/not streaming
     * by its {@code SimulcastReceiver}.
     */
    static final int TIMEOUT_ON_FRAME_COUNT = 5;

    /**
     * The pool of threads utilized by this class.
     */
    private static final ExecutorService executorService = ExecutorUtils
        .newCachedThreadPool(true, SimulcastReceiver.class.getName());

    /**
     * Helper object that <tt>SwitchingSimulcastSender</tt> instances use to
     * build JSON messages.
     */
    private static final SimulcastMessagesMapper mapper
        = new SimulcastMessagesMapper();

    /**
     * The <tt>SimulcastEngine</tt> that owns this receiver.
     */
    private final SimulcastEngine simulcastEngine;

    /**
     * The simulcast layers of this <tt>VideoChannel</tt>.
     */
    private SimulcastLayer[] simulcastLayers;

    /**
     * Indicates whether we're receiving native or non-native simulcast from the
     * associated endpoint. It determines whether the bridge should send
     * messages over the data channels to manage the non-native simulcast. In
     * the case of native simulcast, there's nothing to do for the bridge.
     *
     * NOTE that at the time of this writing we only support native simulcast.
     * Last time we tried non-native simulcast there was no way to limit the
     * bitrate of lower layer streams and thus there was no point in
     * implementing non-native simulcast.
     *
     * NOTE^2 This has changed recently with the webrtc stack automatically
     * limiting the stream bitrate based on its resolution (see commit
     * 1c7d48d431e098ba42fa6bd9f1cfe69a703edee5 in the webrtc git repository).
     * So it might be something that we will want to implement in the future for
     * browsers that don't support native simulcast (Temasys).
     */
    private boolean nativeSimulcast = true;

    /**
     * The history of the order/sequence of receipt of (video) frames by
     * {@link #simulcastLayers}. Used in an attempt to speed up the detection of
     * paused/stopped {@code SimulcastLayer}s by counting (video) frames.
     */
    private final List<SimulcastLayer> simulcastLayerFrameHistory
        = new LinkedList<SimulcastLayer>();

    /**
     * Ctor.
     *
     * @param simulcastEngine the <tt>SimulcastEngine</tt> that owns this
     * receiver.
     */
    public SimulcastReceiver(SimulcastEngine simulcastEngine)
    {
        this.simulcastEngine = simulcastEngine;
    }

    /**
     * Gets the <tt>SimulcastEngine</tt> that owns this receiver.
     *
     * @return the <tt>SimulcastEngine</tt> that owns this receiver.
     */
    public SimulcastEngine getSimulcastEngine()
    {
        return this.simulcastEngine;
    }

    /**
     * Returns true if the endpoint has signaled two or more simulcast layers.
     *
     * @return true if the endpoint has signaled two or more simulcast layers,
     * false otherwise.
     */
    public boolean hasLayers()
    {
        SimulcastLayer[] sl = simulcastLayers;
        return sl != null && sl.length != 0;
    }

    /**
     * Returns a <tt>SimulcastLayer</tt> that is the closest match to the target
     * order, or null if simulcast hasn't been configured for this receiver.
     *
     * @param targetOrder the simulcast layer target order.
     * @return a <tt>SimulcastLayer</tt> that is the closest match to the target
     * order, or null.
     */
    public SimulcastLayer getSimulcastLayer(int targetOrder)
    {
        SimulcastLayer[] layers = getSimulcastLayers();
        if (layers == null || layers.length == 0)
        {
            return null;
        }

        // Iterate through the simulcast layers that we own and return the one
        // that matches best the targetOrder parameter.
        SimulcastLayer next = layers[0];
        for (int i = 1; i < Math.min(targetOrder + 1, layers.length); i++)
        {
            if (!layers[i].isStreaming())
            {
                break;
            }

            next = layers[i];
        }

        return next;
    }

    /**
     * Gets the simulcast layers of this simulcast manager in a new
     * <tt>SortedSet</tt> so that the caller won't have to worry about the
     * structure changing by some other thread.
     *
     * @return the simulcast layers of this receiver in a new sorted set if
     * simulcast is signaled, or null.
     */
    public SimulcastLayer[]  getSimulcastLayers()
    {
        return simulcastLayers;
    }

    /**
     * Sets the simulcast layers for this receiver and fires an event about it.
     *
     * @param simulcastLayers the simulcast layers for this receiver.
     */
    public void setSimulcastLayers(SimulcastLayer[] simulcastLayers)
    {
        this.simulcastLayers = simulcastLayers;

        if (logger.isInfoEnabled())
        {
            if (simulcastLayers == null)
            {
                logInfo("Simulcast disabled.");
            }
            else
            {
                for (SimulcastLayer l : simulcastLayers)
                {
                    logInfo(l.getOrder() + ": " + l.getPrimarySSRC());
                }
            }
        }

        executorService.execute(new Runnable()
        {
            public void run()
            {
                firePropertyChange(SIMULCAST_LAYERS_PNAME, null, null);
            }
        });

        // TODO If simulcastLayers has changed, then simulcastLayerFrameHistory
        // has very likely become irrelevant. In other words, clear
        // simulcastLayerFrameHistory.
    }

    /**
     * Notifies this instance that a <tt>DatagramPacket</tt> packet received on
     * the data <tt>DatagramSocket</tt> of this <tt>Channel</tt> has been
     * accepted for further processing within Jitsi Videobridge.
     *
     * @param pkt the accepted <tt>RawPacket</tt>.
     */
    public void accepted(RawPacket pkt)
    {
        // With native simulcast we don't have a notification when a stream
        // has started/stopped. The simulcast manager implements a timeout
        // for the high quality stream and it needs to be notified when
        // the channel has accepted a datagram packet for the timeout to
        // function correctly.

        if (!hasLayers() || pkt == null)
        {
            return;
        }

        // Find the layer that corresponds to this packet.
        int acceptedSSRC = pkt.getSSRC();
        SimulcastLayer[] layers = getSimulcastLayers();
        SimulcastLayer acceptedLayer = null;
        for (SimulcastLayer layer : layers)
        {
            // We only care about the primary SSRC and not the RTX ssrc (or
            // future FEC ssrc).
            if ((int) layer.getPrimarySSRC() == acceptedSSRC)
            {
                acceptedLayer = layer;
                break;
            }
        }

        // If this is not an RTP packet or if we can't find an accepted
        // layer, log and return as it makes no sense to continue in this
        // situation.
        if (acceptedLayer == null)
        {
            return;
        }

        // There are sequences of packets with increasing timestamps but without
        // the marker bit set. Supposedly, they are probes to detect whether the
        // bandwidth may increase. We think that they should cause neither the
        // start nor the stop of any SimulcastLayer.

        // XXX There's RawPacket#getPayloadLength() but the implementation
        // includes pkt.paddingSize at the time of this writing and we do not
        // know whether that's going to stay that way.
        int pktPayloadLength = pkt.getLength() - pkt.getHeaderLength();
        int pktPaddingSize = pkt.getPaddingSize();

        if (pktPayloadLength <= pktPaddingSize)
        {
            /*if (logger.isTraceEnabled())
            {
                logger.trace(
                        "pkt.payloadLength= " + pktPayloadLength
                            + " <= pkt.paddingSize= " + pktPaddingSize);
            }*/
            return;
        }

        // NOTE(gp) we expect the base layer to be always on, so we never touch
        // it or starve it.

        // XXX Refer to the implementation of
        // SimulcastLayer#touch(boolean, RawPacket) for an explanation of why we
        // chose to use a return value.
        boolean frameStarted = acceptedLayer.touch(pkt);
        if (frameStarted)
            simulcastLayerFrameStarted(acceptedLayer, pkt, layers);
    }

    /**
     * Maybe send a data channel command to the associated
     * <tt>Endpoint</tt> to make it start streaming its hq stream, if
     * it's being watched by some receiver.
     */
    public void maybeSendStartHighQualityStreamCommand()
    {
        if (nativeSimulcast || !hasLayers())
        {
            // In native simulcast the client adjusts its layers autonomously so
            // we don't need (nor we can) to control it with data channel
            // messages.
            return;
        }

        Endpoint newEndpoint
            = getSimulcastEngine().getVideoChannel().getEndpoint();
        SimulcastLayer[] newSimulcastLayers = getSimulcastLayers();

        SctpConnection sctpConnection;
        if (newSimulcastLayers == null
            || newSimulcastLayers.length <= 1
                /* newEndpoint != null is implied */
            || (sctpConnection = newEndpoint.getSctpConnection()) == null
            || !sctpConnection.isReady()
            || sctpConnection.isExpired())
        {
            return;
        }

        // we have a new endpoint and it has an SCTP connection that is
        // ready and not expired. if somebody else is watching the new
        // endpoint, start its hq stream.

        boolean startHighQualityStream = false;

        for (Endpoint e : getSimulcastEngine().getVideoChannel().getContent()
            .getConference().getEndpoints())
        {
            // TODO(gp) need some synchronization here. What if the
            // selected endpoint changes while we're in the loop?

            if (e == newEndpoint)
                continue;

            Endpoint eSelectedEndpoint = e.getEffectivelySelectedEndpoint();

            if (newEndpoint == eSelectedEndpoint)
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

                    logDebug(
                        sc.toString().replaceAll("\\s+", " "));
                }

                startHighQualityStream = true;
                break;
            }
        }

        if (startHighQualityStream)
        {
            // TODO(gp) this assumes only a single hq stream.

            logDebug(
                getSimulcastEngine().getVideoChannel().getEndpoint().getID()
                    + " notifies " + newEndpoint.getID()
                    + " to start its HQ stream.");

            SimulcastLayer hqLayer
                = newSimulcastLayers[newSimulcastLayers.length - 1];;
            StartSimulcastLayerCommand command
                = new StartSimulcastLayerCommand(hqLayer);
            String json = mapper.toJson(command);

            try
            {
                newEndpoint.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logError(
                    newEndpoint.getID()
                        + " failed to send message on data channel.",
                    e);
            }
        }
    }

    /**
     * Maybe send a data channel command to he associated simulcast sender to
     * make it stop streaming its hq stream, if it's not being watched by any
     * participant.
     */
    public void maybeSendStopHighQualityStreamCommand()
    {
        if (nativeSimulcast || !hasLayers())
        {
            // In native simulcast the client adjusts its layers autonomously so
            // we don't need (nor we can) to control it with data channel
            // messages.
            return;
        }

        Endpoint oldEndpoint
            = getSimulcastEngine().getVideoChannel().getEndpoint();

        SimulcastLayer[] oldSimulcastLayers = getSimulcastLayers();

        SctpConnection sctpConnection;
        if (oldSimulcastLayers != null
            && oldSimulcastLayers.length > 1
                /* oldEndpoint != null is implied*/
            && (sctpConnection = oldEndpoint.getSctpConnection()) != null
            && sctpConnection.isReady()
            && !sctpConnection.isExpired())
        {
            // we have an old endpoint and it has an SCTP connection that is
            // ready and not expired. if nobody else is watching the old
            // endpoint, stop its hq stream.

            boolean stopHighQualityStream = true;
            for (Endpoint e : getSimulcastEngine().getVideoChannel()
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

                logDebug(getSimulcastEngine().getVideoChannel().getEndpoint().getID() +
                    " notifies " + oldEndpoint.getID() + " to stop " +
                    "its HQ stream.");

                SimulcastLayer hqLayer
                    = oldSimulcastLayers[oldSimulcastLayers.length - 1];

                StopSimulcastLayerCommand command
                    = new StopSimulcastLayerCommand(hqLayer);

                String json = mapper.toJson(command);

                try
                {
                    oldEndpoint.sendMessageOnDataChannel(json);
                }
                catch (IOException e1)
                {
                    logError(oldEndpoint.getID() + " failed to send " +
                        "message on data channel.", e1);
                }
            }
        }
    }

    private void logDebug(String msg)
    {
        if (logger.isDebugEnabled())
        {
            msg = getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.debug(msg);
        }
    }

    private void logWarn(String msg)
    {
        if (logger.isWarnEnabled())
        {
            msg = getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.warn(msg);
        }
    }

    private void logError(String msg, Throwable e)
    {
        msg = getSimulcastEngine().getVideoChannel()
            .getEndpoint().getID() + ": " + msg;
        logger.error(msg, e);
    }

    private void logInfo(String msg)
    {
        if (logger.isInfoEnabled())
        {
            msg = getSimulcastEngine().getVideoChannel()
                .getEndpoint().getID() + ": " + msg;
            logger.info(msg);
        }
    }

    /**
     * Notifies this {@code SimulcastReceiver} that a specific
     * {@code SimulcastReceiver} has detected the start of a new video frame in
     * the RTP stream that it represents. Determines whether any of
     * {@link #simulcastLayers} other than {@code source} have been
     * paused/stopped by the remote peer. The determination is based on counting
     * (video) frames.
     *
     * @param source the {@code SimulcastLayer} which is the source of the event
     * i.e. which has detected the start of a new video frame in the RTP stream
     * that it represents
     * @param pkt the {@code RawPacket} which was received by {@code source} and
     * possibly influenced the decision that a new view frame was started in the
     * RTP stream represented by {@code source}
     * @param layers the set of {@code SimulcastLayer}s managed by this
     * {@code SimulcastReceiver}. Explicitly provided to the method in order to
     * avoid invocations of {@link #getSimulcastLayers()} because the latter
     * makes a copy at the time of this writing.
     */
    private void simulcastLayerFrameStarted(
            SimulcastLayer source,
            RawPacket pkt,
            SimulcastLayer[] layers)
    {
        // Allow the value of the constant TIMEOUT_ON_FRAME_COUNT to disable (at
        // compile time) the frame-based approach to the detection of layer
        // drops.
        if (TIMEOUT_ON_FRAME_COUNT <= 1)
            return;

        // Timeouts in layers caused by source may occur only based on the span
        // (of time or received frames) during which source has received
        // TIMEOUT_ON_FRAME_COUNT number of frames. The current method
        // invocation signals the receipt of 1 frame by source.
        int indexOfLastSourceOccurrenceInHistory = -1;
        int sourceFrameCount = 0;
        int ix = 0;

        for (Iterator<SimulcastLayer> it
                    = simulcastLayerFrameHistory.iterator();
                it.hasNext();
                ++ix)
        {
            if (it.next() == source)
            {
                if (indexOfLastSourceOccurrenceInHistory != -1)
                {
                    // Prune simulcastLayerFrameHistory so that it does not
                    // become unnecessarily long.
                    it.remove();
                }
                else if (++sourceFrameCount >= TIMEOUT_ON_FRAME_COUNT - 1)
                {
                    // The span of TIMEOUT_ON_FRAME_COUNT number of frames
                    // received by source only is to be examined for the
                    // purposes of timeouts. The current method invocations
                    // signals the receipt of 1 frame by source so
                    // TIMEOUT_ON_FRAME_COUNT - 1 occurrences of source in
                    // simulcastLayerFrameHistory is enough.
                    indexOfLastSourceOccurrenceInHistory = ix;
                }
            }
        }

        if (indexOfLastSourceOccurrenceInHistory != -1)
        {
            // Presumably, if a SimulcastLayer is active, all SimulcastLayers
            // before it (according to SimulcastLayer's order) are active as
            // well. Consequently, timeouts may occur in SimulcastLayers which
            // are after source.
            boolean maybeTimeout = false;

            for (SimulcastLayer layer : layers)
            {
                if (maybeTimeout)
                {
                    // There's no point in timing layer out if it's timed out
                    // already.
                    if (layer.isStreaming())
                    {
                        maybeTimeout(
                                source,
                                pkt,
                                layer,
                                indexOfLastSourceOccurrenceInHistory);
                    }
                }
                else if (layer == source)
                {
                    maybeTimeout = true;
                }
            }
        }

        // As previously stated, the current method invocation signals the
        // receipt of 1 frame by source.
        simulcastLayerFrameHistory.add(0, source);
        // TODO Prune simulcastLayerFrameHistory by forgetting so that it does
        // not become too long.
    }

    /**
     * Determines whether {@code effect} has been paused/stopped by the remote
     * peer. The determination is based on counting frames and is triggered by
     * the receipt of (a piece of) a new (video) frame by {@code cause}.
     *
     * @param cause the {@code SimulcastLayer} which has received (a piece of) a
     * new (video) frame and has thus triggered a check on {@code effect}
     * @param pkt the {@code RawPacket} which was received by {@code cause} and
     * possibly influenced the decision to trigger a check on {@code effect}
     * @param effect the {@code SimulcastLayer} which is to be checked whether
     * it looks like it has been paused/stopped by the remote peer
     * @param endIndexInSimulcastLayerFrameHistory
     */
    private void maybeTimeout(
            SimulcastLayer cause,
            RawPacket pkt,
            SimulcastLayer effect,
            int endIndexInSimulcastLayerFrameHistory)
    {
        Iterator<SimulcastLayer> it = simulcastLayerFrameHistory.iterator();
        boolean timeout = true;

        for (int ix = 0;
                it.hasNext() && ix < endIndexInSimulcastLayerFrameHistory;
                ++ix)
        {
            if (it.next() == effect)
            {
                timeout = false;
                break;
            }
        }
        if (timeout)
        {
            effect.maybeTimeout(pkt);

            if (!effect.isStreaming())
            {
                // Since effect has been determined to have been paused/stopped
                // by the remote peer, its possible presence in
                // simulcastLayerFrameHistory is irrelevant now. In other words,
                // remove effect from simulcastLayerFrameHistory.
                while (it.hasNext())
                {
                    if (it.next() == effect)
                        it.remove();
                }
            }
        }
    }
}

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
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.bouncycastle.crypto.tls.*;
import org.jetbrains.annotations.*;
import org.jitsi.rtp.rtcp.rtcpfb.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.sctp.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi_modified.sctp4j.*;

import java.beans.*;
import java.io.*;
import java.lang.ref.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * Represents an endpoint of a participant in a <tt>Conference</tt>.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Pawel Domas
 * @author George Politis
 */
public class Endpoint
    extends AbstractEndpoint
{
    /**
     * The name of the <tt>Endpoint</tt> property <tt>pinnedEndpoint</tt> which
     * specifies the JID of the currently pinned <tt>Endpoint</tt> of this
     * <tt>Endpoint</tt>.
     */
    public static final String PINNED_ENDPOINTS_PROPERTY_NAME
        = Endpoint.class.getName() + ".pinnedEndpoints";

    /**
     * The name of the <tt>Endpoint</tt> property <tt>selectedEndpoint</tt>
     * which specifies the JID of the currently selected <tt>Endpoint</tt> of
     * this <tt>Endpoint</tt>.
     */
    public static final String SELECTED_ENDPOINTS_PROPERTY_NAME
        = Endpoint.class.getName() + ".selectedEndpoints";

    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger
            = Logger.getLogger(Endpoint.class);

    /**
     * The logger for the instance.
     */
    private final Logger logger;

    /**
     * The set of IDs of the pinned endpoints of this {@code Endpoint}.
     */
    private Set<String> pinnedEndpoints = new HashSet<>();

    /**
     * The set of currently selected <tt>Endpoint</tt>s at this
     * <tt>Endpoint</tt>.
     */
    private Set<String> selectedEndpoints = new HashSet<>();

    /**
     * The {@link SctpManager} instance we'll use to manage the SCTP connection
     */
    private SctpManager sctpManager;

    private final SctpHandler sctpHandler = new SctpHandler();

    private final DataChannelHandler dataChannelHandler = new DataChannelHandler();

    private CompletableFuture<Boolean> onTransportManagerSet = new CompletableFuture<>();

    private final EndpointMessageTransport messageTransport;

    /**
     * A count of how many endpoints have 'selected' this endpoint
     */
    private AtomicInteger selectedCount = new AtomicInteger(0);

    private final BitrateController bitrateController;

    private final BandwidthProbing bandwidthProbing;

    private DataChannelStack dataChannelStack;

    /**
     * This {@link Endpoint}'s transport manager.
     * Since it contains an ICE Agent we don't want to initialize it
     * prematurely, or more than once.
     *
     */
    private IceDtlsTransportManager transportManager;

    /**
     * The exception thrown from the attempt to initialize the transport manager,
     * if any.
     */
    private IOException transportManagerException = null;

    /**
     * Public for now since the channel needs to reach in and grab it
     */
    // XXX public
    public Transceiver transceiver;

    /**
     * The list of {@link ChannelShim}s associated with this endpoint. This
     * allows us to expire the endpoint once all of its 'channels' have been
     * removed.
     */
    final List<WeakReference<ChannelShim>> channelShims
            = new LinkedList<>();

    /**
     * Whether or not the bridge should be the peer which opens the data channel
     * (as opposed to letting the far peer/client open it).
     */
    private static final boolean OPEN_DATA_LOCALLY = false;

    //TODO(brian): align the recurringrunnable stuff with whatever we end up doing with all the other executors
    private static final RecurringRunnableExecutor recurringRunnableExecutor =
            new RecurringRunnableExecutor(Endpoint.class.getSimpleName());


    /**
     * Initializes a new <tt>Endpoint</tt> instance with a specific (unique)
     * identifier/ID of the endpoint of a participant in a <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt> with which the new instance is to be initialized
     * @param conference
     */
    public Endpoint(String id, Conference conference)
    {
        super(conference, id);

        logger = Logger.getLogger(classLogger, conference.getLogger());
        transceiver
                = new Transceiver(
                id,
                TaskPools.CPU_POOL,
                TaskPools.CPU_POOL,
                TaskPools.SCHEDULED_POOL,
                logger);
        transceiver.setIncomingRtpHandler(
                new ConsumerNode("RTP receiver chain handler")
                {
                    @Override
                    protected void consume(@NotNull PacketInfo packetInfo)
                    {
                        handleIncomingRtp(packetInfo);
                    }
                });
        transceiver.setIncomingRtcpHandler(
                new ConsumerNode("RTCP receiver chain handler")
                {
                    @Override
                    public void consume(@NotNull PacketInfo packetInfo)
                    {
                        handleIncomingRtcp(packetInfo);
                    }
                });
        bitrateController = new BitrateController(
                getID(),
                conference.getLogger(),
                transceiver.getDiagnosticContext(),
                this::requestKeyframe);

        messageTransport = new EndpointMessageTransport(this);

        bandwidthProbing
            = new BandwidthProbing((mediaSsrc, numBytes) ->
                    Endpoint.this.transceiver.sendProbing(mediaSsrc, numBytes));
        bandwidthProbing.setDiagnosticContext(
                transceiver.getDiagnosticContext());
        bandwidthProbing.setBitrateController(bitrateController);
        transceiver.setAudioLevelListener(conference.getAudioLevelListener());
        transceiver.onBandwidthEstimateChanged(newValueBps ->
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix +
                        "Estimated bandwidth is now " + newValueBps + " bps.");
            }
            bitrateController.bandwidthChanged(newValueBps);
        });
        transceiver.onBandwidthEstimateChanged(bandwidthProbing);
        conference.encodingsManager.subscribe(this);

        bandwidthProbing.enabled = true;
        recurringRunnableExecutor.registerRecurringRunnable(bandwidthProbing);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EndpointMessageTransport getMessageTransport()
    {
        return messageTransport;
    }

    /**
     * @return the {@link Set} of pinned endpoints, represented as a set of
     * endpoint IDs.
     */
    @Override
    public Set<String> getPinnedEndpoints()
    {
        return pinnedEndpoints;
    }

    void pinnedEndpointsChanged(Set<String> newPinnedEndpoints)
    {
        // Check if that's different to what we think the pinned endpoints are.
        Set<String> oldPinnedEndpoints = this.pinnedEndpoints;
        if (!oldPinnedEndpoints.equals(newPinnedEndpoints))
        {
            this.pinnedEndpoints = newPinnedEndpoints;

            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix + "Pinned "
                    + Arrays.toString(pinnedEndpoints.toArray()));
            }

            bitrateController.setPinnedEndpointIds(pinnedEndpoints);

            firePropertyChange(PINNED_ENDPOINTS_PROPERTY_NAME,
                oldPinnedEndpoints, pinnedEndpoints);
        }
    }

    void selectedEndpointsChanged(Set<String> newSelectedEndpoints)
    {
        // Check if that's different to what we think the pinned endpoints are.
        Set<String> oldSelectedEndpoints = this.selectedEndpoints;
        if (!oldSelectedEndpoints.equals(newSelectedEndpoints))
        {
            this.selectedEndpoints = newSelectedEndpoints;

            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix + "Selected "
                    + Arrays.toString(selectedEndpoints.toArray()));
            }

            bitrateController.setSelectedEndpointIds(Collections.unmodifiableSet(selectedEndpoints));

            firePropertyChange(SELECTED_ENDPOINTS_PROPERTY_NAME,
                oldSelectedEndpoints, selectedEndpoints);
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt)
    {
        if (Conference.ENDPOINTS_PROPERTY_NAME.equals(evt.getPropertyName()))
        {
            bitrateController.endpointOrderingChanged(getConference().getSpeechActivity().getEndpoints());
        }
    }

    /**
     * Sends a specific <tt>String</tt> <tt>msg</tt> over the data channel of
     * this <tt>Endpoint</tt>.
     *
     * @param msg message text to send.
     * @throws IOException
     */
    @Override
    public void sendMessage(String msg)
        throws IOException
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.sendMessage(msg);
        }
    }

    @Override
    public void setLastN(Integer lastN)
    {
        super.setLastN(lastN);
        bitrateController.setLastN(lastN);
    }

    @Override
    public void setMaxReceiveFrameHeightPx(int maxReceiveFrameHeightPx)
    {
        super.setMaxReceiveFrameHeightPx(maxReceiveFrameHeightPx);
        bitrateController.setMaxRxFrameHeightPx(maxReceiveFrameHeightPx);
        bitrateController.constraintsChanged();
    }

    public void setLocalSsrc(MediaType mediaType, long ssrc)
    {
        transceiver.setLocalSsrc(mediaType, ssrc);
        if (MediaType.VIDEO.equals(mediaType))
        {
            bandwidthProbing.senderSsrc = ssrc;
        }
    }

    @Override
    public boolean wants(PacketInfo packetInfo, String sourceEndpointId)
    {
        if (super.wants(packetInfo, sourceEndpointId))
        {
            if (packetInfo.getPacket() instanceof AudioRtpPacket)
            {
                return true;
            }
            RawPacket packet = PacketExtensionsKt.toRawPacket(packetInfo.getPacket());
            return bitrateController.accept(packet);
        }
        return false;
    }

    public void sendRtp(PacketInfo packetInfo)
    {
        //TODO(brian): need to declare this here (not as a member, due to the fact that will be
        // called from multiple threads).  in the future hopefully we can get rid of the need for
        // this array
        RawPacket[] packets = new RawPacket[1];
        Packet packet = packetInfo.getPacket();
        if (packet instanceof VideoRtpPacket)
        {
            packets[0] = PacketExtensionsKt.toRawPacket(packet);
            //TODO(brian): we lose all information in packetinfo here, unfortunately, because
            // the bitratecontroller can return more than/less than what was passed in (and in
            // different order) so we can't just reassign a transformed packet back into its
            // proper packetinfo.  need to change those classes to work with the new packet
            // types
            RawPacket[] res = bitrateController.getRTPTransformer().transform(packets);
            for (RawPacket pkt : res)
            {
                if (pkt == null)
                {
                    continue;
                }
                //TODO(brian): we can clean this up once the transformer is moved over
                VideoRtpPacket videoPacket =
                        org.jitsi.rtp.rtp.RtpPacket.Companion.fromBuffer(PacketExtensionsKt.getByteBuffer(pkt))
                                .toOtherRtpPacketType(VideoRtpPacket::new);
                transceiver.sendRtp(new PacketInfo(videoPacket));
            }
        }
        else
        {
            // By default just add it to the sender's queue
            transceiver.sendRtp(packetInfo);
        }
    }

    /**
     * Handle an SRTP packet which has just been received (i.e. not processed by the
     * incoming pipeline)
     * @param srtpPacket
     */
    public void srtpPacketReceived(PacketInfo srtpPacket)
    {
        transceiver.handleIncomingPacket(srtpPacket);
    }

    /**
     * Handle a DTLS app packet (that is, a packet of some other protocol sent over DTLS)
     * which has just been received
     * @param dtlsAppPacket
     */
    public void dtlsAppPacketReceived(PacketInfo dtlsAppPacket)
    {
        sctpHandler.consume(dtlsAppPacket);
    }

    /**
     * Set the handler which will send packets ready to go out onto the network
     * @param handler
     */
    public void setOutgoingSrtpPacketHandler(PacketHandler handler)
    {
        transceiver.setOutgoingPacketHandler(handler);
    }

    public void setSrtpInformation(int chosenSrtpProtectionProfile, TlsContext tlsContext) {
        transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsContext);
    }

    public void addPayloadType(PayloadType payloadType)
    {
        transceiver.addPayloadType(payloadType);
        bitrateController.addPayloadType(payloadType);
    }

    @Override
    public long getLastActivity()
    {
        PacketIOActivity packetIOActivity = this.transceiver.getPacketIOActivity();
        return packetIOActivity.getLastOverallActivityTimestampMs();
    }

    /**
     * Previously, an endpoint expired when all of its channels did.  Channels now only exist in their 'shim'
     * form for backwards compatibility, so to find out whether or not the endpoint expired, we'll check the
     * activity timestamps from the transceiver and use the largest of the expire times set in the channel shims.
     */
    @Override
    public boolean shouldExpire()
    {
        boolean iceFailed
                = transportManager != null && transportManager.hasIceFailed();
        if (iceFailed)
        {
            logger.warn(logPrefix + "Allowing to expire because ICE failed.");
            return true;
        }

        PacketIOActivity packetIOActivity
                = this.transceiver.getPacketIOActivity();

        int maxExpireTimeSecsFromChannelShims = channelShims.stream()
                .map(WeakReference::get)
                .filter(Objects::nonNull)
                .map(ChannelShim::getExpire)
                .mapToInt(exp -> exp)
                .max()
                .orElse(0);

        long lastActivity
                = packetIOActivity.getLastOverallActivityTimestampMs();
        if (lastActivity <= 0)
        {
            // We haven't seen any activity yet. If this continues ICE will
            // eventually fail (which is handled above).
            return false;
        }

        long now = System.currentTimeMillis();
        if (Duration.ofMillis(now - lastActivity).getSeconds()
                > maxExpireTimeSecsFromChannelShims)
        {
            logger.info(logPrefix +
                    "Allowing to expire because of no activity in over " +
                    maxExpireTimeSecsFromChannelShims + " seconds.");
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expire()
    {
        super.expire();

        try
        {
            this.transceiver.stop();
            if (logger.isDebugEnabled())
            {
                logger.debug(transceiver.getNodeStats().prettyPrint(0));
            }

            transceiver.teardown();

            AbstractEndpointMessageTransport messageTransport
                    = getMessageTransport();
            if (messageTransport != null)
            {
                messageTransport.close();
            }
            if (sctpManager != null)
            {
                sctpManager.closeConnection();
            }
        }
        catch (Exception e)
        {
            logger.error(logPrefix +
                    "Exception while expiring: " + e.toString());
        }
        bandwidthProbing.enabled = false;
        recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing);

        if (transportManager != null)
        {
            transportManager.close();
        }

        logger.info(logPrefix + "Expired.");
    }

    public void createSctpConnection()
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(logPrefix + "Creating SCTP manager.");
        }
        // Create the SctpManager and provide it a method for sending SCTP data
        this.sctpManager = new SctpManager(
                (data, offset, length) -> {
                    PacketInfo packet =
                        new PacketInfo(
                            new UnparsedPacket(
                                    ByteBuffer.wrap(data, offset, length)));
                    transportManager.sendDtlsData(packet);
                    return 0;
                }
        );
        sctpHandler.setSctpManager(sctpManager);
        // NOTE(brian): as far as I know we always act as the 'server' for sctp connections, but if not we can make
        // which type we use dynamic
        SctpServerSocket socket = sctpManager.createServerSocket();
        socket.eventHandler = new SctpSocket.SctpSocketEventHandler()
        {
            @Override
            public void onReady()
            {
                logger.info(logPrefix +
                        "SCTP connection is ready, creating the Data channel stack");
                dataChannelStack = new DataChannelStack((data, sid, ppid) -> socket.send(data, true, sid, ppid));
                dataChannelStack.onDataChannelStackEvents(dataChannel ->
                {
                    logger.info(logPrefix + "Remote side opened a data channel.");
                    Endpoint.this.messageTransport.setDataChannel(dataChannel);
                });
                dataChannelHandler.setDataChannelStack(dataChannelStack);
                if (OPEN_DATA_LOCALLY)
                {
                    logger.info(logPrefix + "Will open the data channel.");
                    DataChannel dataChannel = dataChannelStack.createDataChannel(
                            DataChannelProtocolConstants.RELIABLE,
                            0,
                            0,
                            0,
                            "default");
                    Endpoint.this.messageTransport.setDataChannel(dataChannel);
                    dataChannel.open();
                }
                else
                {
                    logger.info(logPrefix +
                        "Will wait for the remote side to open the data channel.");
                }
            }

            @Override
            public void onDisconnected()
            {
                logger.info(logPrefix + "SCTP connection is disconnected.");
            }
        };
        socket.dataCallback = (data, sid, ssn, tsn, ppid, context, flags) -> {
            // We assume all data coming over SCTP will be datachannel data
            DataChannelPacket dcp
                    = new DataChannelPacket(
                            ByteBuffer.wrap(data), sid, (int)ppid);
            // Post the rest of the task here because the current context is holding a lock inside
            // the SctpSocket which can cause a deadlock if two endpoints are trying to send
            // datachannel messages to one another (with stats broadcasting it can happen
            // often)
            TaskPools.IO_POOL.execute(() -> dataChannelHandler.consume(new PacketInfo(dcp)));
        };
        socket.listen();
        // We don't want to block the calling thread on the onTransportManagerSet future completing
        // to add the onDtlsHandshakeComplete handler, so we'll asynchronously run the code which
        // adds the onDtlsHandshakeComplete handler from the IO pool.
        onTransportManagerSet.thenRunAsync(() -> {
            transportManager.onDtlsHandshakeComplete(() -> {
                // We don't want to block the thread calling onDtlsHandshakeComplete so run
                // the socket acceptance in an IO pool thread
                //TODO(brian): we should have a common 'notifier'/'publisher' interface that
                // has notify/notifyAsync logic so we don't have to worry about this everywhere
                TaskPools.IO_POOL.submit(() -> {
                    while (!socket.accept())
                    {
                        try
                        {
                            Thread.sleep(100);
                        } catch (InterruptedException e)
                        {
                            break;
                        }
                    }
                    if (logger.isDebugEnabled())
                    {
                        logger.debug(logPrefix +
                                "SCTP socket " + socket.hashCode() +
                                " accepted connection.");
                    }
                });
            });
        }, TaskPools.IO_POOL);
    }

    /**
     * Checks whether a WebSocket connection with a specific password string
     * should be accepted for this {@link Endpoint}.
     * @param password the
     * @return {@code true} iff the password matches.
     */
    public boolean acceptWebSocket(String password)
    {
        String icePassword = getIcePassword();
        if (icePassword == null || !icePassword.equals(password))
        {
            logger.warn(logPrefix +
                    "Incoming web socket request with an invalid password." +
                    "Expected: " + icePassword + ", received " + password);
            return false;
        }

        return true;
    }

    /**
     * Notifies this {@link Endpoint} that a specific {@link ColibriWebSocket}
     * instance associated with it has connected.
     * @param ws the {@link ColibriWebSocket} which has connected.
     */
    public void onWebSocketConnect(ColibriWebSocket ws)
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.onWebSocketConnect(ws);
        }
    }

    /**
     * Notifies this {@link Endpoint} that a specific {@link ColibriWebSocket}
     * instance associated with it has been closed.
     * @param ws the {@link ColibriWebSocket} which has been closed.
     */
    public void onWebSocketClose(
            ColibriWebSocket ws, int statusCode, String reason)
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.onWebSocketClose(ws, statusCode, reason);
        }
    }

    /**
     * Notifies this {@link Endpoint} that a message has been received from a
     * specific {@link ColibriWebSocket} instance associated with it.
     * @param ws the {@link ColibriWebSocket} from which a message was received.
     */
    public void onWebSocketText(ColibriWebSocket ws, String message)
    {
        EndpointMessageTransport messageTransport
            = getMessageTransport();
        if (messageTransport != null)
        {
            messageTransport.onWebSocketText(ws, message);
        }
    }

    /**
     * @return the password of the ICE Agent associated with this
     * {@link Endpoint}.
     */
    private String getIcePassword()
    {
        return transportManager == null
                ? null : transportManager.getIcePassword();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementSelectedCount()
    {
        int newValue = selectedCount.incrementAndGet();
        if (newValue == 1)
        {
            String selectedUpdate = createSelectedUpdateMessage(true);
            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix +
                        "Is now selected, sending message: " + selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error(logPrefix +
                        "Error sending SelectedUpdate message: " + e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementSelectedCount()
    {
        int newValue = selectedCount.decrementAndGet();
        if (newValue == 0)
        {
            String selectedUpdate = createSelectedUpdateMessage(false);
            if (logger.isDebugEnabled())
            {
                logger.debug(logPrefix +
                        "Is no longer selected, sending message: " +
                        selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error(logPrefix +
                        "Error sending SelectedUpdate message: " + e);
            }
        }
    }

    /**
     * Gets this {@link Endpoint}'s transport manager, initializing it if it
     * wasn't already initialized. If there was a previous unsuccessful attempt
     * to initialize it, re-throws the same exception.
     *
     * @return this {@link Endpoint}'s transport manager.
     *
     * @throws IOException if the transport manager fails to initialize.
     */
    public IceDtlsTransportManager getTransportManager()
        throws IOException
    {
        if (transportManager != null)
        {
            return transportManager;
        }
        else if (transportManagerException != null)
        {
            // We've already tried and failed to initialize the TM.
            throw transportManagerException;
        }
        else
        {
            try
            {
                transportManager = new IceDtlsTransportManager(this);
                onTransportManagerSet.complete(true);
                return transportManager;
            }
            catch (IOException ioe)
            {
                throw transportManagerException = ioe;
            }
        }
    }

    /**
     * Sets the remote transport information (ICE candidates, DTLS fingerprints).
     *
     * @param transportInfo the XML extension which contains the remote
     * transport information.
     * @throws IOException if the endpoint's transport manager failed to
     * initialize.
     */
    public void setTransportInfo(IceUdpTransportPacketExtension transportInfo)
            throws IOException
    {
        getTransportManager().startConnectivityEstablishment(transportInfo);
    }

    /**
     * A node which can be placed in the pipeline to cache SCTP packets until the SCTPManager
     * is ready to handle them.
     */
    private class SctpHandler extends ConsumerNode
    {
        private final Object sctpManagerLock = new Object();
        public SctpManager sctpManager = null;
        public BlockingQueue<PacketInfo> cachedSctpPackets = new LinkedBlockingQueue<>();
        public SctpHandler()
        {
            super("SCTP handler");
        }

        @Override
        protected void consume(PacketInfo packetInfo)
        {
            synchronized (sctpManagerLock)
            {
                if (sctpManager == null)
                {
                    cachedSctpPackets.add(packetInfo);
                }
                else
                {
                    sctpManager.handleIncomingSctp(packetInfo);
                }
            }
        }

        public void setSctpManager(SctpManager sctpManager)
        {
            // Submit this to the pool since we wait on the lock and process any
            // cached packets here as well
            TaskPools.IO_POOL.submit(() -> {
                // We grab the lock here so that we can set the SCTP manager and
                // process any previously-cached packets as an atomic operation.
                // It also prevents another thread from coming in via {@link #doProcessPackets}
                // and processing packets at the same time in another thread, which would
                // be a problem.
                synchronized (sctpManagerLock)
                {
                    this.sctpManager = sctpManager;
                    cachedSctpPackets.forEach(sctpManager::handleIncomingSctp);
                    cachedSctpPackets.clear();
                }
            });
        }
    }

    /**
     * A node which can be placed in the pipeline to cache Data channel packets until
     * the DataChannelStack is ready to handle them.
     */
    private class DataChannelHandler extends ConsumerNode
    {
        private final Object dataChannelStackLock = new Object();
        public DataChannelStack dataChannelStack = null;
        public BlockingQueue<PacketInfo> cachedDataChannelPackets = new LinkedBlockingQueue<>();
        public DataChannelHandler()
        {
            super("Data channel handler");
        }

        @Override
        protected void consume(PacketInfo packetInfo)
        {
            synchronized (dataChannelStackLock)
            {
                if (packetInfo.getPacket() instanceof DataChannelPacket)
                {
                    if (dataChannelStack == null)
                    {
                        cachedDataChannelPackets.add(packetInfo);
                    }
                    else
                    {
                        DataChannelPacket dcp
                                = (DataChannelPacket) packetInfo.getPacket();
                        dataChannelStack.onIncomingDataChannelPacket(
                                dcp.getBuffer(), dcp.sid, dcp.ppid);
                    }
                }
            }
        }

        public void setDataChannelStack(DataChannelStack dataChannelStack)
        {
            // Submit this to the pool since we wait on the lock and process any
            // cached packets here as well
            TaskPools.IO_POOL.submit(() -> {
                // We grab the lock here so that we can set the SCTP manager and
                // process any previously-cached packets as an atomic operation.
                // It also prevents another thread from coming in via {@link #doProcessPackets}
                // and processing packets at the same time in another thread, which would
                // be a problem.
                synchronized (dataChannelStackLock)
                {
                    this.dataChannelStack = dataChannelStack;
                    cachedDataChannelPackets.forEach(packetInfo -> {
                        DataChannelPacket dcp = (DataChannelPacket)packetInfo.getPacket();
                        //TODO(brian): have datachannelstack accept DataChannelPackets?
                        dataChannelStack.onIncomingDataChannelPacket(dcp.getBuffer(), dcp.sid, dcp.ppid);
                    });
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     * @throws IOException if the transport manager fails to initialize.
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
            throws IOException
    {
        getTransportManager().describe(channelBundle);
    }

    private void requestKeyframe(long ssrc)
    {
        AbstractEndpoint ep = getConference().findEndpointByReceiveSSRC(ssrc, MediaType.VIDEO);
        if (ep instanceof Endpoint)
        {
            ((Endpoint) ep).transceiver.requestKeyFrame(ssrc);
        }
    }

    private void setMediaStreamTracks(MediaStreamTrackDesc[] mediaStreamTracks)
    {
        if (transceiver.setMediaStreamTracks(mediaStreamTracks))
        {
            firePropertyChange(
                    ENDPOINT_CHANGED_PROPERTY_NAME,
                    null,
                    null);
        }
    }

    @Override
    public MediaStreamTrackDesc[] getMediaStreamTracks()
    {
        return transceiver.getMediaStreamTracks();
    }

    @Override
    public void recreateMediaStreamTracks()
    {
        ChannelShim videoChannel = getChannelOfMediaType(MediaType.VIDEO);
        if (videoChannel != null)
        {
            MediaStreamTrackDesc[] tracks =
                    MediaStreamTrackFactory.createMediaStreamTracks(
                            videoChannel.getSources(),
                            videoChannel.getSourceGroups());
            setMediaStreamTracks(tracks);
        }
    }

    /**
     * Gets this {@link AbstractEndpoint}'s channel of media type
     * {@code mediaType} (although it's not strictly enforced, endpoints have
     * at most one channel with a given media type).
     *
     * @param mediaType the media type of the channel.
     *
     * @return the channel.
     */
    private ChannelShim getChannelOfMediaType(MediaType mediaType)
    {
        return
                channelShims.stream()
                        .filter(Objects::nonNull)
                        .map(WeakReference::get)
                        .filter(
                                channel ->
                                        channel != null &&
                                                channel.getMediaType().equals(mediaType))
                        .findAny().orElse(null);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receivesSsrc(long ssrc)
    {
        return transceiver.receivesSsrc(ssrc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addReceiveSsrc(long ssrc)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(logPrefix + "Adding receive ssrc " + ssrc);
        }
        transceiver.addReceiveSsrc(ssrc);
    }

    private void handleIncomingRtcp(PacketInfo packetInfo)
    {
        // We don't need to copy RTCP packets for each dest like we do with RTP because each one
        // will only have a single destination
        // TODO: getEndpointsFast()?
        getConference().getEndpoints().forEach(endpoint -> {
            if (packetInfo.getPacket() instanceof RtcpFbPacket)
            {
                RtcpFbPacket rtcpPacket = (RtcpFbPacket) packetInfo.getPacket();
                // TODO route RTCP to Octo too
                if (endpoint instanceof Endpoint && endpoint.receivesSsrc(rtcpPacket.getMediaSourceSsrc()))
                {
                    ((Endpoint) endpoint).transceiver.sendRtcp(rtcpPacket);
                }
            }
        });
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     * @param packetInfo the packet.
     */
    protected void handleIncomingRtp(PacketInfo packetInfo)
    {
        getConference().handleIncomingRtp(packetInfo, this);
    }

    @Override
    public void onNewSsrcAssociation(
            String endpointId,
            long primarySsrc,
            long secondarySsrc,
            SsrcAssociationType type)
    {
        transceiver.addSsrcAssociation(primarySsrc, secondarySsrc, type);
    }

    public void addChannel(ChannelShim channelShim)
    {
        synchronized (channelShims)
        {
            channelShims.add(new WeakReference<>(channelShim));
        }
    }

    public void removeChannel(ChannelShim channelShim)
    {
        synchronized (channelShims)
        {
            for (Iterator<WeakReference<ChannelShim>> i = channelShims.iterator(); i.hasNext();)
            {
                ChannelShim existingChannelShim = i.next().get();
                if (existingChannelShim != null && existingChannelShim.equals(channelShim)) {
                    i.remove();
                }
            }
            if (channelShims.isEmpty())
            {
                expire();
            }
        }
    }

}

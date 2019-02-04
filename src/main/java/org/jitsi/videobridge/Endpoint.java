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

import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.concurrent.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.sctp.*;
import org.jitsi.videobridge.util.*;
import org.jitsi_modified.sctp4j.*;
import org.jitsi_modified.service.neomedia.rtp.*;

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
@SuppressWarnings("JavadocReference")
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

    private AudioLevelListenerImpl audioLevelListener;

    private CompletableFuture<Boolean> onTransportManagerSet = new CompletableFuture<>();

    /**
     * The password of the ICE Agent associated with this endpoint: note that
     * without bundle an endpoint might have multiple channels with different
     * ICE Agents. In this case one of the channels will be chosen (in an
     * unspecified way).
     *
     * Initialized lazily.
     */
    private String icePassword;

    private final EndpointMessageTransport messageTransport;

    /**
     * A count of how many endpoints have 'selected' this endpoint
     */
    private AtomicInteger selectedCount = new AtomicInteger(0);

    private final BitrateController bitrateController;

    private final BandwidthProbing bandwidthProbing;

    //TODO(brian): align the recurringrunnable stuff with whatever we end up doing with all the other executors
    private static final RecurringRunnableExecutor recurringRunnableExecutor =
            new RecurringRunnableExecutor(Endpoint.class.getSimpleName());

    private void requestKeyframe(long ssrc)
    {
        AbstractEndpoint ep = getConference().findEndpointByReceiveSSRC(ssrc, MediaType.VIDEO);
        if (ep != null)
        {
            ep.transceiver.requestKeyFrame(ssrc);
        }
    }

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

        bitrateController = new BitrateController(
                getID(),
                conference.getLogger(),
                transceiver.getDiagnosticContext(),
                this::requestKeyframe);

        messageTransport = new EndpointMessageTransport(this);

        audioLevelListener = new AudioLevelListenerImpl(conference.getSpeechActivity());
        bandwidthProbing = new BandwidthProbing(new BandwidthProbing.ProbingDataSender()
        {
            @Override
            public int sendProbing(long mediaSsrc, int numBytes)
            {
                return Endpoint.this.transceiver.sendProbing(mediaSsrc, numBytes);
            }
        });
        bandwidthProbing.setDiagnosticContext(transceiver.getDiagnosticContext());
        bandwidthProbing.setBitrateController(bitrateController);
        transceiver.setAudioLevelListener(audioLevelListener);
        transceiver.onBandwidthEstimateChanged(new BandwidthEstimator.Listener()
        {
            @Override
            public void bandwidthEstimationChanged(long newValueBps)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Endpoint " + getID() + "'s estimated bandwidth is now " + newValueBps + " bps");
                }
                bitrateController.bandwidthChanged(newValueBps);
            }
        });
        transceiver.onBandwidthEstimateChanged(bandwidthProbing);

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
                logger.debug(getID() + " pinned "
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
                logger.debug(getID() + " selected "
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
        super.propertyChange(evt);
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

    @Override
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

    @Override
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
                VideoRtpPacket videoPacket = new VideoRtpPacket(PacketExtensionsKt.getByteBuffer(pkt));
                super.sendRtp(new PacketInfo(videoPacket));
            }
        }
        else
        {
            super.sendRtp(packetInfo);
        }
    }

    @Override
    public void addPayloadType(PayloadType payloadType)
    {
        super.addPayloadType(payloadType);
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
        PacketIOActivity packetIOActivity = this.transceiver.getPacketIOActivity();

        int maxExpireTimeSecsFromChannelShims = channelShims.stream()
                .map(WeakReference::get)
                .filter(Objects::nonNull)
                .map(ColibriShim.ChannelShim::getExpire)
                .mapToInt(exp -> exp)
                .max()
                .orElse(0);

        long now = System.currentTimeMillis();
        Duration timeSincePacketReceived = Duration.ofMillis(now - packetIOActivity.getLastPacketReceivedTimestampMs());
        Duration timeSincePacketSent = Duration.ofMillis(now - packetIOActivity.getLastPacketSentTimestampMs());

        if (timeSincePacketReceived.getSeconds() > maxExpireTimeSecsFromChannelShims &&
                timeSincePacketSent.getSeconds() > maxExpireTimeSecsFromChannelShims)
        {
            System.out.println("Endpoint " + getID() + " has neither received nor sent a packet in over " +
                    maxExpireTimeSecsFromChannelShims + " seconds, should expire");
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

            AbstractEndpointMessageTransport messageTransport = getMessageTransport();
            if (messageTransport != null)
            {
                messageTransport.close();
            }
            if (sctpManager != null)
            {
                sctpManager.closeConnection();
            }
        } catch (Exception e) {
            logger.error("Exception while expiring endpoint " + getID() + ": " + e.toString());
        }
        bandwidthProbing.enabled = false;
        recurringRunnableExecutor.deRegisterRecurringRunnable(bandwidthProbing);

        logger.info("Endpoint " + getID() + " expired");
    }

    private DataChannelStack dataChannelStack;
    private IceUdpTransportManager transportManager;

    private void readIncomingSctpPackets()
    {
        LinkedBlockingQueue<PacketInfo> sctpPackets =
                ((IceDtlsTransportManager)transportManager).sctpAppPackets;
        while (true) {
            try {
                PacketInfo sctpPacket = sctpPackets.take();
                if (logger.isDebugEnabled())
                {
                    logger.debug("Endpoint " + getID() + " received an incoming sctp packet " +
                            " (size " + sctpPacket.getPacket().getBuffer().limit() + ")");

                }
                if (sctpManager != null)
                {
                    sctpManager.handleIncomingSctp(sctpPacket);
                }
                else
                {
                    logger.warn("Endpoint " + getID() + " received an SCTP packet but the SCTP manager is " +
                            "null, dropping the packet");
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while reading from sctp packet queue: " + e.toString());
            }
        }
    }

    //TODO(brian): not sure if this is the final way we'll associate the transport manager and endpoint/transceiver,
    // but it's a step.
    public void setTransportManager(IceUdpTransportManager transportManager)
    {
        this.transportManager = transportManager;
        ((IceDtlsTransportManager)transportManager).onDtlsHandshakeComplete(() -> {
            logger.info("Endpoint " + getID() + " dtls handshake is complete, starting a reader for incoming SCTP" +
                    " packets");
            //TODO(brian): i think this work is not that CPU intensive, so using the IO pool is ok?
            TaskPools.IO_POOL.submit(this::readIncomingSctpPackets);
        });

        ((IceDtlsTransportManager)transportManager).setTransceiver(this.transceiver);
        onTransportManagerSet.complete(true);
    }

    public void createSctpConnection() {
        logger.info("Endpoint " + getID() + " creating SCTP manager");
        // Create the SctpManager and provide it a method for sending SCTP data
        this.sctpManager = new SctpManager(
                (data, offset, length) -> {
                    PacketInfo packet = new PacketInfo(new UnparsedPacket(ByteBuffer.wrap(data, offset, length)));
                    ((IceDtlsTransportManager)transportManager).sendDtlsData(packet);
                    return 0;
                }
        );
        // NOTE(brian): as far as I know we always act as the 'server' for sctp connections, but if not we can make
        // which type we use dynamic
        SctpServerSocket socket = sctpManager.createServerSocket();
        socket.eventHandler = new SctpSocket.SctpSocketEventHandler()
        {
            @Override
            public void onReady()
            {
                //NOTE(brian): i believe the bridge is responsible for opening the data channel, but if not we can
                // make how we open/wait for the datachannel connection dynamic
                logger.info("Endpoint " + getID() + "'s SCTP connection is ready. Opening data channel");
                //TODO: there's a chance we could miss a data channel open message here if the sctp connection
                // opens and the remote side sends an open channel message before the datachannel has set itself as
                // the handler for data on the sctp connection
                DataChannel dataChannel = dataChannelStack.createDataChannel(
                        DataChannelProtocolConstants.RELIABLE,
                        0,
                        0,
                        0,
                        "default");
                Endpoint.this.messageTransport.setDataChannel(dataChannel);
                dataChannel.open();
            }

            @Override
            public void onDisconnected()
            {
                logger.info("Endpoint " + getID() + "'s SCTP connection is disconnected");
            }
        };
        dataChannelStack = new DataChannelStack(socket);
        dataChannelStack.onDataChannelStackEvents(new DataChannelStack.DataChannelStackEventListener()
        {
            @Override
            public void onDataChannelOpenedRemotely(DataChannel dataChannel)
            {
                logger.info("Remote side opened a data channel.  This is not handled!");
            }
        });
        socket.listen();
        // We don't want to block the calling thread on the onTransportManagerSet future completing
        // to add the onDtlsHandshakeComplete handler, so we'll asynchronously run the code which
        // adds the onDtlsHandshakeComplete handler from the IO pool.
        onTransportManagerSet.thenRunAsync(() -> {
            ((IceDtlsTransportManager)transportManager).onDtlsHandshakeComplete(() -> {
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
                    logger.info("SCTP socket " + socket.hashCode() + " accepted connection");
                });
            });
        }, TaskPools.IO_POOL);
    }

    /**
     * Checks whether a WebSocket connection with a specific password string
     * should be accepted for this {@link Endpoint}.
     * @param password the
     * @return {@code true} iff the password matches and the WebSocket
     */
    public boolean acceptWebSocket(String password)
    {
        String icePassword = getIcePassword();
        if (icePassword == null || !icePassword.equals(password))
        {
            logger.warn("Incoming web socket request with an invalid password."
                            + "Expected: " + icePassword
                            + ", received " + password);
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
        if (icePassword != null)
        {
            return icePassword;
        }

        if (transportManager != null)
        {
            String password = transportManager.getIcePassword();
            if (password != null)
            {
                this.icePassword = password;
                return password;
            }
        }

        return null;
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
                logger.debug("Endpoint " + getID() + " is now "
                    + "selected, sending message: " + selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error("Error sending SelectedUpdate message: " + e);
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
                logger.debug("Endpoint " + getID() + " is no longer "
                    + "selected, sending message: " + selectedUpdate);
            }
            try
            {
                sendMessage(selectedUpdate);
            }
            catch (IOException e)
            {
                logger.error("Error sending SelectedUpdate message: " + e);
            }
        }
    }
}

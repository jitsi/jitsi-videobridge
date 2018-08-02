/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.videobridge.brian_mediastream;

import org.jitsi.impl.neomedia.codec.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.stats.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.stats.*;
import sun.reflect.generics.reflectiveObjects.*;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * @author bbaldino
 */
public class NewMediaStream implements MediaStream
{
    private static void printStack(String context) {
        Exception e = new Exception();
        StringBuilder b = new StringBuilder();
        b.append("BRIAN: ").append(context).append(" called from:\n");
        for (int i = 1; i < e.getStackTrace().length; ++i) {
            b.append(e.getStackTrace()[i]).append("\n");
        }
        System.out.println(b.toString());
    }

    protected Map<Byte, MediaFormat> payloadTypeMap = new HashMap<>();
    protected Map<Byte, RTPExtension> headerExtensionMap = new HashMap<>();


    @Override
    public String getName()
    {
        throw new NotImplementedException();
    }

    @Override
    public void setName(String s)
    {
        printStack("setName");
    }

    @Override
    public void setProperty(String s, Object o)
    {
        // From what I can tell only used to set the name
        printStack("setProperty " + s + " = " + o);
    }

    @Override
    public Object getProperty(String s)
    {
        throw new NotImplementedException();
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener propertyChangeListener)
    {
        printStack("addPropertyChangeListener");
    }

    @Override
    public void start()
    {
        throw new NotImplementedException();
    }

    @Override
    public void stop()
    {
        throw new NotImplementedException();
    }

    @Override
    public void close()
    {
        printStack("close");
    }

    @Override
    public StreamConnector.Protocol getTransportProtocol()
    {
        throw new NotImplementedException();
    }

    @Override
    public SrtpControl getSrtpControl()
    {
        throw new NotImplementedException();
    }

    @Override
    public MediaDirection getDirection()
    {
        printStack("getDirection");
        return MediaDirection.SENDRECV;
    }

    @Override
    public void setDirection(MediaDirection mediaDirection)
    {
        throw new NotImplementedException();
    }

    @Override
    public void removeReceiveStreamForSsrc(long l)
    {
        throw new NotImplementedException();
    }

    @Override
    public void setSSRCFactory(SSRCFactory ssrcFactory)
    {
        throw new NotImplementedException();
    }

    @Override
    public MediaStreamTrackReceiver getMediaStreamTrackReceiver()
    {
        throw new NotImplementedException();
    }

    @Override
    public void setTransportCCEngine(TransportCCEngine transportCCEngine)
    {
        printStack("setTransportCCEngine " + transportCCEngine);
        // I think this gets set externally because it needs to be shared(?)
    }

    @Override
    public void setRTPTranslator(RTPTranslator rtpTranslator)
    {
        // There is one RTPTranslator per content.  It calls into the willWrite/accept chain
        // for each channel to determine if a packet should go to that destination.  This is
        // the current main 'switchboard' of packets, so we'll want something like this, but
        // done differently (eventually, at least)
        // Don't know yet if we need to do anything with this one
        printStack("setRTPTranslator: " + rtpTranslator);
    }

    @Override
    public TransformEngineChain getTransformEngineChain()
    {
        throw new NotImplementedException();
    }

    @Override
    public RTPTranslator getRTPTranslator()
    {
        throw new NotImplementedException();
    }

    @Override
    public void setConnector(StreamConnector streamConnector)
    {
        // Old stream takes the connector here and sets it on the
        // dtls stack and that's how the dtls stack knows to start.
        // but we want the dtls stack to happen before this
        // entirely anyway?  Maybe the connector here
        // can have a socket which has the post-DTL
        // data?  We could have the transport manager
        // set up the DTLS bits (it already creates the dtls control now)
        //TODO: we'll grab the incoming/outgoing socket from here
        throw new NotImplementedException();
    }

    @Override
    public void setTarget(MediaStreamTarget mediaStreamTarget)
    {
        //TODO: grab something from here?
        throw new NotImplementedException();
    }

    @Override
    public MediaStreamTarget getTarget()
    {
        throw new NotImplementedException();
    }

    @Override
    public InetSocketAddress getRemoteControlAddress()
    {
        throw new NotImplementedException();
    }

    @Override
    public InetSocketAddress getRemoteDataAddress()
    {
        throw new NotImplementedException();
    }

    @Override
    public long getLocalSourceID()
    {
        // Do we need this?
        throw new NotImplementedException();
    }

    @Override
    public long getRemoteSourceID()
    {
        throw new NotImplementedException();
    }

    @Override
    public List<Long> getRemoteSourceIDs()
    {
        throw new NotImplementedException();
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener propertyChangeListener)
    {
        // Do we need this?
        throw new NotImplementedException();
    }

    @Override
    public void injectPacket(RawPacket rawPacket, boolean b, TransformEngine transformEngine)
    {
        // Do we need this?
        throw new NotImplementedException();
    }

    @Override
    public boolean isStarted()
    {
        // Do we need this?
        throw new NotImplementedException();
    }

    @Override
    public boolean isMute()
    {
        // Do we need this?
        throw new NotImplementedException();
    }

    @Override
    public void setMute(boolean b)
    {
        throw new NotImplementedException();
    }

    @Override
    public MediaStreamStats2 getMediaStreamStats()
    {
//        printStack("getMediaStreamStats");
        return null;
    }

    @Override
    public void setExternalTransformer(TransformEngine transformEngine)
    {
        printStack("setExternalTransformer");
    }

    @Override
    public void addRTPExtension(byte b, RTPExtension rtpExtension)
    {
        printStack("addRTPExtension: " + b + " -> " + rtpExtension);
        headerExtensionMap.put(b, rtpExtension);
    }

    @Override
    public Map<Byte, RTPExtension> getActiveRTPExtensions()
    {
        // Do we really need to expose these?
        throw new NotImplementedException();
    }

    @Override
    public void clearRTPExtensions()
    {
        printStack("clearRTPExtensions");
        headerExtensionMap.clear();
    }

    @Override
    public void addDynamicRTPPayloadType(byte b, MediaFormat mediaFormat)
    {
        printStack("addDynamicRTPPayloadType " + b + " -> " + mediaFormat);
        payloadTypeMap.put(b, mediaFormat);
    }

    @Override
    public void addDynamicRTPPayloadTypeOverride(byte b, byte b1)
    {
        throw new NotImplementedException();
    }

    @Override
    public Map<Byte, MediaFormat> getDynamicRTPPayloadTypes()
    {
        // Do we really need to expose these?
        throw new NotImplementedException();
    }

    @Override
    public byte getDynamicRTPPayloadType(String s)
    {
        throw new NotImplementedException();
    }

    @Override
    public void clearDynamicRTPPayloadTypes()
    {
        printStack("clearDynamicRTPPayloadTypes");
        payloadTypeMap.clear();
    }

    @Override
    public MediaFormat getFormat(byte b)
    {
        throw new NotImplementedException();
    }

    @Override
    public MediaFormat getFormat()
    {
        throw new NotImplementedException();
    }

    @Override
    public void setFormat(MediaFormat mediaFormat)
    {
        throw new NotImplementedException();
    }

    @Override
    public MediaDevice getDevice()
    {
        throw new NotImplementedException();
    }

    @Override
    public void setDevice(MediaDevice mediaDevice)
    {
        throw new NotImplementedException();
    }

    @Override
    public StreamRTPManager getStreamRTPManager()
    {
        throw new NotImplementedException();
    }

    @Override
    public REDBlock getPrimaryREDBlock(RawPacket rawPacket)
    {
        // In the stream because it uses the payload types to check if the packet
        // is already a RED packet (so it can find the block), otherwise it
        // just creates a new REDBlock
        throw new NotImplementedException();
    }

    @Override
    public REDBlock getPrimaryREDBlock(ByteArrayBuffer byteArrayBuffer)
    {
        throw new NotImplementedException();
    }

    @Override
    public RetransmissionRequester getRetransmissionRequester()
    {
        // Used by RtpChannel to set the send ssrc
        throw new NotImplementedException();
    }

    @Override
    public boolean isKeyFrame(RawPacket rawPacket)
    {
        // In the stream because it checks the header extensions to see
        // if frame marking is enabled (or will use payload types to help
        // detect whether or not it's a key frame)
        throw new NotImplementedException();
    }

    @Override
    public boolean isKeyFrame(byte[] bytes, int i, int i1)
    {
        throw new NotImplementedException();
    }
}

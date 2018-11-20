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

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.event.*;

/**
 * Implements an <tt>RtpChannel</tt> with <tt>MediaType.AUDIO</tt>.
 *
 * @author Lyubomir Marinov
 */
public class AudioChannel
    extends RtpChannel
{
    /**
     * Initializes a new <tt>AudioChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>AudioChannel</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     * @param transportNamespace the namespace of transport used by this
     * channel. Can be either {@link IceUdpTransportPacketExtension#NAMESPACE}
     * or {@link RawUdpTransportPacketExtension#NAMESPACE}.
     * @param initiator the value to use for the initiator field, or
     * <tt>null</tt> to use the default value.
     */
    public AudioChannel(Content content,
                        String id,
                        String channelBundleId,
                        String transportNamespace,
                        Boolean initiator)
    {
        super(content, id, channelBundleId, transportNamespace, initiator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void removeStreamListeners()
    {
        super.removeStreamListeners();

        try
        {
            MediaStream stream = getStream();

            if (stream instanceof AudioMediaStream)
            {
                ((AudioMediaStream) stream).setCsrcAudioLevelListener(null);
            }
        }
        catch (Throwable t)
        {
            if (t instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            else if (t instanceof ThreadDeath)
            {
                throw (ThreadDeath) t;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void rtpLevelRelayTypeChanged(
            RTPLevelRelayType oldValue,
            RTPLevelRelayType newValue)
    {
        super.rtpLevelRelayTypeChanged(oldValue, newValue);

        if (RTPLevelRelayType.MIXER.equals(newValue))
        {
            Content content = getContent();

            if (MediaType.AUDIO.equals(content.getMediaType()))
            {
                // Allow the Jitsi Videobridge server to send the audio levels
                // of the contributing sources to the telephony conference
                // participants.
                MediaStream stream = getStream();
                MediaDevice device = content.getMixer();
                List<RTPExtension> rtpExtensions
                    = device.getSupportedExtensions();

                if (rtpExtensions.size() == 1)
                {
                    stream.addRTPExtension((byte) 1, rtpExtensions.get(0));
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     * </p>
     * Registers this channel's {@link AudioChannelAudioLevelListener} with
     * the media stream.
     */
    @Override
    protected void configureStream(MediaStream stream)
    {
        if (stream instanceof AudioMediaStream)
        {
            ((AudioMediaStream) stream)
                .setCsrcAudioLevelListener(
                    new AudioChannelAudioLevelListener(this));
        }
    }
}

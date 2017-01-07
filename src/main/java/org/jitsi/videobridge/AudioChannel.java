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
     * The <tt>CsrcAudioLevelListener</tt> instance which is set on
     * <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setCsrcAudioLevelListener(
     * CsrcAudioLevelListener)} in order to receive the audio levels of the
     * contributing sources.
     */
    private CsrcAudioLevelListener csrcAudioLevelListener;

    /**
     * The <tt>SimpleAudioLevelListener</tt> instance which is set on
     * <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setStreamAudioLevelListener(
     * SimpleAudioLevelListener)} in order to have the audio levels of the
     * contributing sources calculated and to end enable the functionality of
     * {@code #lastN}.
     */
    private SimpleAudioLevelListener streamAudioLevelListener;

    /**
     * The {@link LipSyncHack} from the {@link VideoChannel}.
     */
    private LipSyncHack associatedLipSyncHack;

    /**
     * A boolean that indicates whether or not we've fetched the
     * {@link LipSyncHack} from the {@link VideoChannel}.
     */
    private boolean fetchedLipSyncHack = false;

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
     * @throws Exception if an error occurs while initializing the new instance
     */
    public AudioChannel(Content content,
                        String id,
                        String channelBundleId,
                        String transportNamespace,
                        Boolean initiator)
        throws Exception
    {
        super(content, id, channelBundleId, transportNamespace, initiator);
    }

    /**
     * Gets the <tt>CsrcAudioLevelListener</tt> instance which is set on
     * <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setCsrcAudioLevelListener(
     * CsrcAudioLevelListener)} in order to receive the audio levels of the
     * contributing sources.
     *
     * @return the <tt>CsrcAudioLevelListener</tt> instance
     */
    private CsrcAudioLevelListener getCsrcAudioLevelListener()
    {
        if (csrcAudioLevelListener == null)
        {
            csrcAudioLevelListener
                = new CsrcAudioLevelListener()
                {
                    @Override
                    public void audioLevelsReceived(long[] levels)
                    {
                        streamAudioLevelsReceived(levels);
                    }
                };
        }
        return csrcAudioLevelListener;
    }

    /**
     * Gets the <tt>SimpleAudioLevelListener</tt> instance which is set on
     * <tt>AudioMediaStream</tt> via
     * {@link AudioMediaStream#setStreamAudioLevelListener(
     * SimpleAudioLevelListener)} in order to have the audio levels of the
     * contributing sources calculated and to enable the functionality of
     * {@code #lastN}.
     *
     * @return the <tt>SimpleAudioLevelListener</tt> instance
     */
    private SimpleAudioLevelListener getStreamAudioLevelListener()
    {
        if (streamAudioLevelListener == null)
        {
            streamAudioLevelListener
                = new SimpleAudioLevelListener()
                {
                    @Override
                    public void audioLevelChanged(int level)
                    {
                        streamAudioLevelChanged(level);
                    }
                };
        }
        return streamAudioLevelListener;
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
                AudioMediaStream audioStream = (AudioMediaStream) stream;
                CsrcAudioLevelListener csrcAudioLevelListener
                    = this.csrcAudioLevelListener;
                SimpleAudioLevelListener streamAudioLevelListener
                    = this.streamAudioLevelListener;

                if (csrcAudioLevelListener != null)
                {
                    audioStream.setCsrcAudioLevelListener(
                        csrcAudioLevelListener);
                }
                if (streamAudioLevelListener != null)
                {
                    audioStream.setStreamAudioLevelListener(
                        streamAudioLevelListener);
                }
            }
        }
        catch (Throwable t)
        {
            if (t instanceof InterruptedException)
                Thread.currentThread().interrupt();
            else if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
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
                    stream.addRTPExtension((byte) 1, rtpExtensions.get(0));

                ((AudioMediaStream) stream).setStreamAudioLevelListener(
                        getStreamAudioLevelListener());
            }
        }
    }

    /**
     * Notifies this instance about a change in the audio level of the (remote)
     * endpoint/conference participant associated with this <tt>Channel</tt>.
     *
     * @param level the new/current audio level of the (remote)
     * endpoint/conference participant associated with this <tt>Channel</tt>
     */
    private void streamAudioLevelChanged(int level)
    {
        /*
         * Whatever, the Jitsi Videobridge is not interested in the audio
         * levels. However, an existing/non-null streamAudioLevelListener has to
         * be set on an AudioMediaStream in order to have the audio levels of
         * the contributing sources calculated at all.
         */
    }

    /**
     * Notifies this instance that {@link #stream} has received the audio levels
     * of the contributors to this <tt>Channel</tt>.
     *
     * @param levels a <tt>long</tt> array in which the elements at the even
     * indices specify the CSRC IDs and the elements at the odd indices
     * specify the respective audio levels
     */
    private void streamAudioLevelsReceived(long[] levels)
    {
        if (levels != null)
        {
            /*
             * Forward the audio levels of the contributors to this Channel to
             * the active/dominant speaker detection/identification algorithm.
             */
            int[] receiveSSRCs = getReceiveSSRCs();

            if (receiveSSRCs.length != 0)
            {
                /*
                 * The SSRCs are at the even indices, their audio levels at the
                 * immediately subsequent odd indices.
                 */
                for (int i = 0, count = levels.length / 2; i < count; i++)
                {
                    int i2 = i * 2;
                    long ssrc = levels[i2];
                    /*
                     * The contributing SSRCs may not all be from sources
                     * associated with this Channel and we're only interested in
                     * the latter here.
                     */
                    boolean isReceiveSSRC = false;

                    for (int receiveSSRC : receiveSSRCs)
                    {
                        if (ssrc == (0xFFFFFFFFL & receiveSSRC))
                        {
                            isReceiveSSRC = true;
                            break;
                        }
                    }
                    if (isReceiveSSRC)
                    {
                        ConferenceSpeechActivity conferenceSpeechActivity
                            = this.conferenceSpeechActivity;

                        if (conferenceSpeechActivity != null)
                        {
                            int level = (int) levels[i2 + 1];

                            conferenceSpeechActivity.levelChanged(
                                    this,
                                    ssrc,
                                    level);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void addRtpHeaderExtension(
            RTPHdrExtPacketExtension rtpHdrExtPacketExtension)
    {
        super.addRtpHeaderExtension(rtpHdrExtPacketExtension);

        if (RTPExtension.SSRC_AUDIO_LEVEL_URN
                .equals(rtpHdrExtPacketExtension.getURI().toString()))
        {
            MediaStream stream = getStream();
            if (stream != null && stream instanceof AudioMediaStream)
            {
                ((AudioMediaStream) stream).setCsrcAudioLevelListener(
                        getCsrcAudioLevelListener());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean rtpTranslatorWillWrite(
        boolean data,
        byte[] buffer, int offset, int length,
        RtpChannel source)
    {
        if (!fetchedLipSyncHack)
        {
            fetchedLipSyncHack = true;

            List<RtpChannel> channels = getEndpoint()
                .getChannels(MediaType.VIDEO);

            if (channels != null && !channels.isEmpty())
            {
                associatedLipSyncHack
                    = ((VideoChannel)channels.get(0)).getLipSyncHack();
            }
        }

        if (associatedLipSyncHack != null)
        {
            associatedLipSyncHack.onRTPTranslatorWillWriteAudio(
                data, buffer, offset, length, source);
        }

        return true;
    }
}

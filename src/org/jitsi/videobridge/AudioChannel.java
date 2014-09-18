/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.net.*;
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
     * {@link #lastN}.
     */
    private SimpleAudioLevelListener streamAudioLevelListener;

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
     * @throws Exception if an error occurs while initializing the new instance
     */
    public AudioChannel(Content content, String id, String channelBundleId)
        throws Exception
    {
        super(content, id, channelBundleId);
    }

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
     * @throws Exception if an error occurs while initializing the new instance
     */
    public AudioChannel(Content content, String id)
        throws Exception
    {
        this(content, id, null);
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
     * {@link #lastN}.
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
    protected void payloadTypesSet(
            List<PayloadTypePacketExtension> payloadTypes,
            boolean googleChrome)
    {
        super.payloadTypesSet(payloadTypes, googleChrome);

        MediaStream stream;

        if (googleChrome
                && ((stream = getStream()) instanceof AudioMediaStream))
        {
            // TODO Use RTPExtension.SSRC_AUDIO_LEVEL_URN instead of
            // RTPExtension.CSRC_AUDIO_LEVEL_URN while we do not support the
            // negotiation of RTP header extension IDs.
            URI uri;

            try
            {
                uri = new URI(RTPExtension.SSRC_AUDIO_LEVEL_URN);
            }
            catch (URISyntaxException e)
            {
                uri = null;
            }
            if (uri != null)
            {
                stream.addRTPExtension((byte) 1, new RTPExtension(uri));
                // Feed the client-to-mixer audio levels into the algorithm
                // which detects/identifies the active/dominant speaker.
                ((AudioMediaStream) stream).setCsrcAudioLevelListener(
                        getCsrcAudioLevelListener());
            }
        }
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
}

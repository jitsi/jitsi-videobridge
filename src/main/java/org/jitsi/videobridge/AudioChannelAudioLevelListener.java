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
package org.jitsi.videobridge;

import org.jitsi.service.neomedia.event.*;

/**
 * An {@link CsrcAudioLevelListener} implementation which is associated with
 * a particular {@link RtpChannel} and forwards received audio levels to the
 * channel's {@link ConferenceSpeechActivity}.
 *
 * This is split from {@link AudioChannel} because the functionality is needed
 * in both {@link AudioChannel} and
 * {@link org.jitsi.videobridge.octo.OctoChannel}.
 *
 * @author Boris Grozev
 */
public class AudioChannelAudioLevelListener
    implements CsrcAudioLevelListener
{
    /**
     * The (audio) {@link RtpChannel} associated with this
     * {@link AudioChannelAudioLevelListener}.
     */
    private final RtpChannel channel;

    /**
     * Initializes a new {@link AudioChannelAudioLevelListener} instance.
     * @param channel the associated (audio) {@link RtpChannel}.
     */
    public AudioChannelAudioLevelListener(RtpChannel channel)
    {
        this.channel = channel;
    }

    /**
     * Notifies this instance that the
     * {@link org.jitsi.service.neomedia.AudioMediaStream} that is is registered
     * with has received the audio levels of the contributors to the associated
     * {@link RtpChannel}.
     *
     * @param levels a <tt>long</tt> array in which the elements at the even
     * indices specify the CSRC IDs and the elements at the odd indices
     * specify the respective audio levels.
     */
    public void audioLevelsReceived(long[] levels)
    {
        if (levels != null)
        {
            /*
             * Forward the audio levels of the contributors to this Channel to
             * the active/dominant speaker detection/identification algorithm.
             */
            int[] receiveSSRCs = channel.getReceiveSSRCs();

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
                        if (ssrc == (0xFFFF_FFFFL & receiveSSRC))
                        {
                            isReceiveSSRC = true;
                            break;
                        }
                    }
                    if (isReceiveSSRC)
                    {
                        ConferenceSpeechActivity conferenceSpeechActivity
                            = channel.conferenceSpeechActivity;

                        if (conferenceSpeechActivity != null)
                        {
                            int level = (int) levels[i2 + 1];

                            conferenceSpeechActivity.levelChanged(
                                this.channel,
                                ssrc,
                                level);
                        }
                    }
                }
            }
        }
    }
}

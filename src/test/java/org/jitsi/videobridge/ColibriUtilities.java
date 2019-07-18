/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jxmpp.jid.*;

/**
 * FIXME merge with utility used by the focus
 *
 * @author Pawel Domas
 */
public class ColibriUtilities
{
    /**
     * Creates {@link ColibriConferenceIQ} with audio content and empty channel
     * IQ. Conference ID is empty hence it can be used to created new conference
     * on the bridge.
     *
     * @param focusJid conference focus owner.
     *
     * @return {@link ColibriConferenceIQ} with audio content and empty channel
     *         IQ.
     */
    public static ColibriConferenceIQ createConferenceIq(Jid focusJid)
    {
        ColibriConferenceIQ confIq = new ColibriConferenceIQ();

        confIq.setFrom(focusJid);

        ColibriConferenceIQ.Content audioContent
            = new ColibriConferenceIQ.Content(MediaType.AUDIO.toString());

        String endpointId = "dummy_ep_id";
        ColibriConferenceIQ.Channel channel
            = new ColibriConferenceIQ.Channel();
        channel.setEndpoint(endpointId);
        channel.setChannelBundleId(endpointId);

        audioContent.addChannel(channel);

        confIq.addContent(audioContent);

        return confIq;
    }
}

/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import org.jitsi.service.neomedia.*;

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
    public static ColibriConferenceIQ createConferenceIq(String focusJid)
    {
        ColibriConferenceIQ confIq = new ColibriConferenceIQ();

        confIq.setFrom(focusJid);

        ColibriConferenceIQ.Content audioContent
            = new ColibriConferenceIQ.Content(MediaType.AUDIO.toString());

        ColibriConferenceIQ.Channel channel
            = new ColibriConferenceIQ.Channel();

        audioContent.addChannel(channel);

        confIq.addContent(audioContent);

        return confIq;
    }
}

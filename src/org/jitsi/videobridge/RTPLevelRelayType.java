/*
 * Jitsi VideoBridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

/**
 * Defines the RTP-level relay types as specified by RFC 3550 "RTP: A Transport
 * Protocol for Real-Time Applications" in section 2.3 "Mixers and Translators".
 *
 * @author Lyubomir Marinov
 */
public enum RTPLevelRelayType
{
    /**
     * The type of RTP-level relay which performs content mixing on the received
     * media. In order to mix the received content, the relay will usually decode
     * the received RTP and RTCP packets into raw media and will subsequently
     * generate new RTP and RTCP packets to send the new media which represents
     * the mix of the received content.
     */
    MIXER,

    /**
     * The type of RTP-level relay which does not perform content mixing on the
     * received media and rather forwards the received RTP and RTCP packets. The
     * relay will usually not decode the received RTP and RTCP into raw media.
     */
    TRANSLATOR
}

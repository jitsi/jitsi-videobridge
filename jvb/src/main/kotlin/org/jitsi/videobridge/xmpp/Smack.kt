/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
package org.jitsi.videobridge.xmpp

import org.jitsi.videobridge.xmpp.config.XmppClientConnectionConfig
import org.jitsi.xmpp.extensions.DefaultPacketExtensionProvider
import org.jitsi.xmpp.extensions.colibri.ColibriStatsIqProvider
import org.jitsi.xmpp.extensions.colibri.ForcefulShutdownIqProvider
import org.jitsi.xmpp.extensions.colibri.GracefulShutdownIqProvider
import org.jitsi.xmpp.extensions.colibri2.IqProviderUtils
import org.jitsi.xmpp.extensions.health.HealthCheckIQProvider
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceCandidatePacketExtension
import org.jitsi.xmpp.extensions.jingle.IceRtcpmuxPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.provider.ProviderManager
import org.jxmpp.stringprep.XmppStringPrepUtil

object Smack {
    fun initialize() {
        org.jitsi.xmpp.Smack.initialize()

        XmppStringPrepUtil.setMaxCacheSizes(XmppClientConnectionConfig.config.jidCacheSize)

        registerProviders()
    }

    private fun registerProviders() {
        // <force-shutdown>
        ForcefulShutdownIqProvider.registerIQProvider()
        // <graceful-shutdown>
        GracefulShutdownIqProvider.registerIQProvider()
        // <stats>
        ColibriStatsIqProvider() // registers itself with Smack
        // ICE-UDP <transport>
        ProviderManager.addExtensionProvider(
            IceUdpTransportPacketExtension.ELEMENT,
            IceUdpTransportPacketExtension.NAMESPACE,
            DefaultPacketExtensionProvider(IceUdpTransportPacketExtension::class.java)
        )
        // ICE-UDP <candidate xmlns=urn:xmpp:jingle:transports:ice-udp:1">
        ProviderManager.addExtensionProvider(
            IceCandidatePacketExtension.ELEMENT,
            IceCandidatePacketExtension.NAMESPACE,
            DefaultPacketExtensionProvider(IceCandidatePacketExtension::class.java)
        )
        // ICE <rtcp-mux/>
        ProviderManager.addExtensionProvider(
            IceRtcpmuxPacketExtension.ELEMENT,
            IceRtcpmuxPacketExtension.NAMESPACE,
            DefaultPacketExtensionProvider(IceRtcpmuxPacketExtension::class.java)
        )
        // DTLS-SRTP <fingerprint>
        ProviderManager.addExtensionProvider(
            DtlsFingerprintPacketExtension.ELEMENT,
            DtlsFingerprintPacketExtension.NAMESPACE,
            DefaultPacketExtensionProvider(DtlsFingerprintPacketExtension::class.java)
        )
        // Health-check
        HealthCheckIQProvider.registerIQProvider()
        // Colibri2
        IqProviderUtils.registerProviders()
    }
}

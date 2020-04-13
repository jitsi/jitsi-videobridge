/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.config.newConfigAttributes
import org.jitsi.nlj.Event
import org.jitsi.nlj.FeatureToggleEvent
import org.jitsi.nlj.Features
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.transform.node.PcapWriter
import org.jitsi.utils.config.SimpleProperty
import org.jitsi.utils.logging2.Logger
import java.lang.IllegalStateException
import java.util.Date

class ToggleablePcapWriter(
    val parentLogger: Logger,
    val prefix: String
) {
    private var pcapWriter: PcapWriter? = null

    private val pcapLock = Any()

    fun enable() {
        if (!PcapWriterConfig.Config.pcapEnabled()) {
            throw IllegalStateException("PCAP capture is disabled in configuration")
        }
        synchronized(pcapLock) {
            if (pcapWriter == null) {
                pcapWriter = PcapWriter(parentLogger, "/tmp/$prefix-${Date().toInstant()}.pcap")
            }
        }
    }

    fun disable() {
        synchronized(pcapLock) {
            pcapWriter?.close()
            pcapWriter = null
        }
    }

    fun newObserverNode(): Node = PcapWriterNode("Toggleable pcap writer: $prefix")

    private inner class PcapWriterNode(name: String) : ObserverNode(name) {
        override fun observe(packetInfo: PacketInfo) {
            pcapWriter?.processPacket(packetInfo)
        }

        override fun handleEvent(event: Event) {
            when (event) {
                is FeatureToggleEvent -> {
                    if (event.feature == Features.TRANSCEIVER_PCAP_DUMP) {
                        if (event.enable) {
                            enable()
                        } else {
                            disable()
                        }
                    }
                }
            }
        }

        override fun trace(f: () -> Unit) = f.invoke()
    }
}

class PcapWriterConfig {
    class Config {
        companion object {

            class PcapEnabledProperty : SimpleProperty<Boolean>(
                newConfigAttributes {
                    readOnce()
                    name("jmt.debug.pcap.enabled")
                }
            )
            private val pcapEnabledProperty =
                PcapEnabledProperty()

            @JvmStatic
            fun pcapEnabled(): Boolean = pcapEnabledProperty.value
        }
    }
}

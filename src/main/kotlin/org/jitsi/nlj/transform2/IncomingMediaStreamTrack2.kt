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
package org.jitsi.nlj.transform2

import org.jitsi.nlj.IncomingMediaStreamTrack
import org.jitsi.nlj.Packet
import kotlin.system.measureNanoTime

class IncomingMediaStreamTrack2 : IncomingMediaStreamTrack {
    val moduleChain: ModuleChain
    var rtpHandler: (Packet) -> Unit = {}

    init {
//        moduleChain = ModuleChainBuilder.build {
//            name("Main chain")
//            module(PacketStatsModule())
//            module(SrtpModule())
//            module(RtpRtcpSplitterModule(
//                rtpPath = ModuleChainBuilder.build {
//                    name("RTP chain")
//                    module(PacketLossMonitorModule())
//                    module(RtpHandlerModule())
//                },
//                rtcpPath = ModuleChainBuilder.build {
//                    name("RTCP chain")
//                    module(RtcpHandlerModule())
//                })
//            )
//        }

        moduleChain = ModuleChainBuilder.chain {
            name("Incoming chain")
            module(PacketStatsModule())
            module(SrtpModule())
            demux {
                name = "RTP/RTCP demuxer"
                addSubChain {
                    predicate(Packet::isRtp)
                    path(ModuleChainBuilder.chain {
                        name("RTP chain")
                        module(PacketLossMonitorModule())
                        module(FecReceiver())
                    })
                }
                addSubChain {
                    predicate(Packet::isRtcp)
                    path(ModuleChainBuilder.build {
                        name("RTCP chain")
                        module(RtcpHandlerModule())
                    })
                }
            }
        }
        moduleChain.findFirst(FecReceiver::class)?.let {
            println("found fec receiver")
            (it as FecReceiver).handlers.add {
                println("fec receiver handler sees it recoevered packet $it")
            }
        }

        // Wishlist(?)
        // chain =
        // ModuleChain()
        //    .module(PacketStatsModule())
        //    .module(SrtpTransformer())
        //    .module(Splitter()
        //        .addSubChain(
        //             isRtp,
        //             ModuleChain()
        //                .module(PacketLossMonitor())
        //                .module(RtpHandler())
        //        )
        //        .addSubChain(
        //            isRtcp,
        //            ModuleChain()
        //                .module(RtcpHandler())
        //        )
        //
        //val chain = PacketStatsModule()
        //chain
        //    .module(SrtpModule()::processPackets)
        //moduleCHain =
        //        PacketStatsModule().module(SrtpModule())
    }
    override fun processPackets(pkts: List<Packet>) {
        val time = measureNanoTime {
            moduleChain.processPackets(pkts)
        }
//        println("Entire transform chain took $time nanos")
    }

    override fun getStats(): String {
        return with (StringBuffer()) {
            append(moduleChain.getStats())
            toString()
        }
    }

//    override fun onRtpPacket(handler: (Packet) -> Unit) {
//        this.rtpHandler = handler
//    }
}

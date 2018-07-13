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
package org.jitsi.nlj

import org.jitsi.nlj.transform2.IncomingMediaStreamTrack2
import org.jitsi.rtp.RtpPacket
import java.lang.Thread.sleep
import java.util.concurrent.Executors

fun main(args: Array<String>) {
    val p = PacketProducer()
    val trackExecutor = Executors.newSingleThreadExecutor()
//    val trackExecutor = Executors.newCachedThreadPool()
    p.addSource(123)
    val stream1 = IncomingMediaStreamTrack2(123, trackExecutor)
    stream1.start()
    p.addDestination({ pkt -> pkt.isRtp && (pkt as RtpPacket).header.ssrc == 123L }, stream1::enqueuePacket)

    p.addSource(456)
    val stream2 = IncomingMediaStreamTrack2(456, trackExecutor)
    stream2.start()
    p.addDestination({ pkt -> pkt.isRtp && (pkt as RtpPacket).header.ssrc == 456L }, stream2::enqueuePacket)

//    p.addSource(789)
//    val stream3 = IncomingMediaStreamTrack2(789, trackExecutor)
//    stream3.start()
//    p.addDestination({ pkt -> pkt.isRtp && (pkt as RtpPacket).header.ssrc == 789L }, stream3::enqueuePacket)


    p.run()

    sleep(10000)
    p.stop()

    println("Producer wrote ${p.packetsWritten} packets")
    println(stream1.getStats())
    println(stream2.getStats())
//    println(stream3.getStats())

}

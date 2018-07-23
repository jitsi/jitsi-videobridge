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
package org.jitsi.nlj.transform2.module

import org.jitsi.nlj.util.EvictingQueue
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpHeaderExtension
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.RtpTwoByteHeaderExtension
import java.nio.ByteBuffer

// Make this look kinda like a two-byte header extension, but it will never
// be read from or written to a buffer. (Should we make RtpHeaderExtension
// more generic to make this easier?) or at least wrap access to the fields
// in getters?
class TimeTagExtension : RtpHeaderExtension() {
    override val data: ByteBuffer
        get() = TODO("not implemented")
    override val id: Int = TimeTagExtension.ID
    override val lengthBytes: Int = 4
    override val size: Int = RtpTwoByteHeaderExtension.HEADER_SIZE + lengthBytes
    val timestamp = System.currentTimeMillis()

    companion object {
        const val ID: Int = 42
    }

    override fun serializeToBuffer(buf: ByteBuffer) {}
}

class TimeTag(val context: String = "") {
    val timestamp = TimeTag.getTime()
    companion object {
        const val ID: String = "TimeTag"
        fun getTime(): Long = System.nanoTime()
        fun getUnit(): String = "nanos"
    }
}

class TimeTaggerModule(private val context: String = "") : Module("TimeTagger") {
    override fun doProcessPackets(p: List<Packet>) {
        p.forEachIf<RtpPacket> {
//            it.header.extensions[TimeTagExtension.ID] = TimeTagExtension()
            val res = it.tags.computeIfAbsent(TimeTag.ID) { mutableListOf<TimeTag>() } as MutableList<TimeTag>
            res.add(TimeTag(context))
        }
        next(p)
    }
}

class TimeTagExtensionReader(private val shouldStrip: Boolean = false) : Module("TimeTag Reader") {
    val durations = mutableListOf<Long>()
    override fun doProcessPackets(p: List<Packet>) {
        val currentTime = System.currentTimeMillis()
        p.forEachIf<RtpPacket> { pkt ->
            pkt.header.getExtension(TimeTagExtension.ID)?.let {
                val duration = currentTime - (it as TimeTagExtension).timestamp
                durations.add(duration)
                if (shouldStrip) {
                    pkt.header.extensions.remove(TimeTagExtension.ID)
                }
            }
        }
        durations.dropLastWhile { durations.size > 10 }
        next(p)
//        println("Average packet time to this point: ${durations.average()}")
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            appendLnIndent(indent + 2, "Average packet time to this point: ${durations.average()} ms")
            toString()
        }
    }
}

class TimeTagReader : Module("TimeTag Reader") {
    val durations = mutableMapOf<String, EvictingQueue<Long>>()
    override fun doProcessPackets(p: List<Packet>) {
        val currentTime = TimeTag.getTime()
        p.forEachIf<RtpPacket> { pkt ->
            pkt.tags.getOrDefault(TimeTag.ID, null)?.let {
                val tags = it as List<TimeTag>
                tags.forEach { tag ->
                    val duration = currentTime - tag.timestamp
                    durations.computeIfAbsent(tag.context) { EvictingQueue(100) }.add(duration)
                }
            }
        }
        next(p)
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            appendLnIndent(indent + 2, "Average packet times:")
            durations.forEach { context, durations ->
                appendLnIndent(indent + 4, "Since $context: ${durations.average()} ${TimeTag.getUnit()}")
            }
            toString()
        }
    }
}

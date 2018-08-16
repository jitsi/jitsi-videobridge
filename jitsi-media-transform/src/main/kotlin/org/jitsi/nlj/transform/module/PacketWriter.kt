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
package org.jitsi.nlj.transform.module

import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.Vp8PayloadDescriptor
import org.jitsi.rtp.util.BufferView
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFileAttributes



class PacketWriter(filePath: String = "/tmp/${System.currentTimeMillis()}.rtpdump") : Module("PacketWriter") {
    val path: Path
    val intBuffer = ByteBuffer.allocate(4)
    val fileWriter: FileChannel
    val ssrcs = mutableSetOf<Long>()
    init {
        path = Paths.get(filePath)
        Files.createFile(path)
        val perms = Files.readAttributes(path, PosixFileAttributes::class.java).permissions()
        perms.add(PosixFilePermission.GROUP_WRITE)
        perms.add(PosixFilePermission.OTHERS_WRITE)
        Files.setPosixFilePermissions(path, perms)
        val fos = FileOutputStream(path.toFile())
        fileWriter = fos.channel
    }
    override fun doProcessPackets(p: List<Packet>) {
        p.forEachAs<RtpPacket> {
//            println("BRIAN: writing packet ${it.header.ssrc} ${it.header.sequenceNumber} size ${it.size} to file $path")
            if (!ssrcs.contains(it.header.ssrc)) {
//                println("BRIAN: writing ssrc ${it.header.ssrc} to file $path")
                ssrcs.add(it.header.ssrc)
            }
            intBuffer.putInt(0, it.size)
//            println("BRIAN: file $path writing size to position ${fileWriter.position()}")
            val sizeWritten = fileWriter.write(intBuffer)
            if (sizeWritten != 4) {
                println("BRIAN: DID NOT WRITE ALL SIZE DATA: expected 4, wrote $sizeWritten")
            }
//            println("BRIAN: writing size ${it.size} and a buffer with position ${it.buf.position()} and limit ${it.buf.limit()}")
//            println("BRIAN: file $path writing buf to position ${fileWriter.position()}")
            val written = fileWriter.write(it.getBuffer())
            if (written != it.getBuffer().limit()) {
                println("BRIAN: DID NOT WRITE ALL DATA: expected ${it.getBuffer().limit()}, wrote $written")
            }
            intBuffer.rewind()
        }
        next(p)
    }
}

fun main(args: Array<String>) {
    val path = Paths.get("/tmp/1534186696314.rtpdump")
    val reader = FileInputStream(path.toFile()).channel
    val intBuffer = ByteBuffer.allocate(4)
//    for (i in 1..10) {
    while (true) {
//        println("Reading length from position ${reader.position()}")
        reader.read(intBuffer)
        val length = intBuffer.getInt(0)
        println("got length $length")
        if (length < 0 || length > 1500) {
            println("Bad length, exiting")
            break
        }
        val packetBuf = ByteBuffer.allocate(length)
//        println("Reading packet from position ${reader.position()}")
        reader.read(packetBuf)
        packetBuf.flip()
        val rtpPacket = RtpPacket(packetBuf)
        println("Read packet ${rtpPacket.header.ssrc} ${rtpPacket.header.sequenceNumber} ${rtpPacket.header.timestamp}")
        val vp8PayloadDescriptor = Vp8PayloadDescriptor(BufferView(rtpPacket.payload.array(), rtpPacket.payload.arrayOffset(), rtpPacket.payload.array().size))
        println("parsed vp8 payload desc: $vp8PayloadDescriptor")
        intBuffer.rewind()
    }


}

package org.jitsi.videobridge.recorder

//import org.ebml.EBMLReader
//import org.ebml.Element
//import org.ebml.MasterElement
//import org.ebml.io.DataSource
//import org.ebml.io.FileDataSource
import org.ebml.io.FileDataWriter
import org.ebml.matroska.MatroskaFileFrame
import org.ebml.matroska.MatroskaFileTrack
import org.ebml.matroska.MatroskaFileTrack.TrackType
import org.ebml.matroska.MatroskaFileWriter
//import org.jitsi.rtp.extensions.get3Bytes
import org.jitsi.utils.logging2.createLogger
import java.io.File
import java.nio.ByteBuffer

class MkaRecorder {
    private val logger = createLogger()
    private val destination: File = File.createTempFile("test", ".mkv").apply {
        logger.warn("Writing to $this")
    }

    private val ioDW = FileDataWriter(destination.path)
    private val writer: MatroskaFileWriter = MatroskaFileWriter(ioDW)
    private val tracks = mutableMapOf<String, MatroskaFileTrack>()

    private var f = 0
    fun startTrack(name: String) {
        val track = MatroskaFileTrack().apply {
            trackNo = tracks.size + 1
            trackType = TrackType.AUDIO
            codecID = "A_OPUS"
            defaultDuration = 20000000
            audio = MatroskaFileTrack.MatroskaAudioTrack().apply {
                channels = 2
                samplingFrequency = 48000F
            }
        }
        tracks[name] = track
        writer.addTrack(track)
    }

    fun addFrame(trackName: String, timecode: Long, payload: ByteArray) {
        val track = tracks[trackName] ?: throw Exception("Track not started")
        val frame = MatroskaFileFrame()
        frame.data = ByteBuffer.wrap(payload)
        frame.trackNo = track.trackNo
        //frame.timecode = timecode / 48
        logger.warn("Add to $trackName timecode=${timecode/48}")
        writer.addFrame(frame)
    }

    fun close() {
        writer.close()
        ioDW.close()
        //testDocTraversal()
        //destination.delete()
    }
//
//    fun testDocTraversal() {
//        val ioDS = FileDataSource(destination.path)
//        val reader = EBMLReader(ioDS)
//        var level0 = reader.readNextElement()
//        while (level0 != null) {
//            traverseElement(level0, ioDS, reader, 0)
//            level0.skipData(ioDS)
//            level0 = reader.readNextElement()
//        }
//    }
//
//    var x = 0
//    private fun traverseElement(levelN: Element?, ioDS: DataSource, reader: EBMLReader, level: Int) {
//        if (levelN == null) {
//            return
//        }
//
//        logger.info("Found element: ${".".repeat(level*2)} ${levelN.elementType.name}")
////        if (levelN.elementType.name == "TimecodeScale") {
////            levelN.readData(ioDS)
////            println("oops: "+ levelN.data.get3Bytes())
////            return
////        }
//        if (levelN.elementType.name == "Timecode") {
//            if (x == 0) { x++} else {
//                levelN.readData(ioDS)
//                if (levelN.data.capacity() == 1)
//                    println("oops: " + levelN.data.get().toInt())
//                else if (levelN.data.capacity() == 2)
//                    println("oops: " + levelN.data.getShort().toInt())
//                else if (levelN.data.capacity() == 3)
//                    println("oops: " + levelN.data.get3Bytes().toInt())
//                else if (levelN.data.capacity() == 4)
//                    println("oops: " + levelN.data.getInt())
//                return
//            }
//        }
//
//        val elemLevel = levelN.elementType.level
//        if (elemLevel != -1) {
//            check(level.toLong() == elemLevel.toLong())
//        }
//        if (levelN is MasterElement) {
//            var levelNPlusOne = levelN.readNextChild(reader)
//            while (levelNPlusOne != null) {
//                traverseElement(levelNPlusOne, ioDS, reader, level + 1)
//                levelNPlusOne.skipData(ioDS)
//                levelNPlusOne = levelN.readNextChild(reader)
//            }
//        }
//    }
}
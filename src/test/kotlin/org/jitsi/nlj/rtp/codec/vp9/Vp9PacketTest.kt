package org.jitsi.nlj.rtp.codec.vp9

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi_modified.impl.neomedia.codec.video.vp9.DePacketizer
import javax.xml.bind.DatatypeConverter

class Vp9PacketTest : ShouldSpec() {
    private data class SampleVp9Packet(
        val description: String,
        val data: ByteArray,
        val isStartOfFrame: Boolean,
        val isEndOfFrame: Boolean,
        val isEndOfPicture: Boolean,
        val isKeyframe: Boolean,
        val isInterPicturePredicted: Boolean,
        val pictureId: Int?,
        val hasExtendedPictureId: Boolean,
        val isUpperLevelReference: Boolean,
        val tid: Int?,
        val sid: Int?,
        val isSwitchingUpPoint: Boolean,
        val usesInterLayerDependency: Boolean,
        val tL0PICIDX: Int?,
        val descriptorSize: Int,
        val scalabilityStructure: RtpEncodingDesc? = null
    ) {
        constructor(
            description: String,
            hexData: String,
            isStartOfFrame: Boolean,
            isEndOfFrame: Boolean,
            isEndOfPicture: Boolean,
            isKeyframe: Boolean,
            isInterPicturePredicted: Boolean,
            pictureId: Int?,
            hasExtendedPictureId: Boolean,
            isUpperLevelReference: Boolean,
            tid: Int?,
            sid: Int?,
            isSwitchingUpPoint: Boolean,
            usesInterLayerDependency: Boolean,
            tL0PICIDX: Int?,
            descriptorSize: Int,
            scalabilityStructure: RtpEncodingDesc? = null
        ) : this(
            description = description,
            data = DatatypeConverter.parseHexBinary(hexData),
            isStartOfFrame = isStartOfFrame,
            isEndOfFrame = isEndOfFrame,
            isEndOfPicture = isEndOfPicture,
            isKeyframe = isKeyframe,
            isInterPicturePredicted = isInterPicturePredicted,
            pictureId = pictureId,
            hasExtendedPictureId = hasExtendedPictureId,
            isUpperLevelReference = isUpperLevelReference,
            tid = tid,
            sid = sid,
            isSwitchingUpPoint = isSwitchingUpPoint,
            usesInterLayerDependency = usesInterLayerDependency,
            tL0PICIDX = tL0PICIDX,
            descriptorSize = descriptorSize,
            scalabilityStructure = scalabilityStructure
        )
    }

    /* Packets captured from Chrome VP9 call */
    private val testPackets = arrayOf(
        /* Live video - Chrome 81 */
        SampleVp9Packet(
            "Chrome: Start of keyframe (with SS) in K-SVC stream",
            // RTP
            "906536b69f3077686098017b" +
                // RTP header extension
                "bede0002" + "3202168751210700" +
                // I=1,P=0,L=1,F=0,B=1,E=0,V=1,Z=0
                "aa" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=0,D=0
                "00" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // Begin SS: N_S=2,Y=1,G=1
                "58" +
                // WIDTH=320
                "0140" +
                // HEIGHT=180
                "00b4" +
                // WIDTH=640
                "0280" +
                // HEIGHT=360
                "0168" +
                // WIDTH=1280
                "0500" +
                // HEIGHT=720
                "02d0" +
                // N_G=4
                "04" +
                // TID=0,U=0,R=1
                "04" +
                // P_DIFF=4
                "04" +
                // TID=2,U=1,R=1
                "54" +
                // P_DIFF=1
                "01" +
                // TID=1,U=1,R=1
                "34" +
                // P_DIFF=2
                "02" +
                // TID=2,U=1,R=1
                "54" +
                // P_DIFF=1
                "01" +
                // VP9 media.  Truncated.
                "834983420013f00b3827f8167858e0907063a8000f",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 0,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = 253,
            descriptorSize = 27,
            scalabilityStructure = RtpEncodingDesc(
                0x6098017bL,
                arrayOf(
                    RtpLayerDesc(0, 0, 0, 180, 7.5),
                    RtpLayerDesc(0, 1, 0, 180, 15.0 /* TODO: dependencies */),
                    RtpLayerDesc(0, 2, 0, 180, 30.0 /* TODO: dependencies */),
                    RtpLayerDesc(0, 0, 1, 360, 7.5 /* TODO: dependencies */),
                    RtpLayerDesc(0, 1, 1, 360, 15.0 /* TODO: dependencies */),
                    RtpLayerDesc(0, 2, 1, 360, 30.0 /* TODO: dependencies */),
                    RtpLayerDesc(0, 0, 2, 720, 7.5 /* TODO: dependencies */),
                    RtpLayerDesc(0, 1, 2, 720, 15.0 /* TODO: dependencies */),
                    RtpLayerDesc(0, 2, 2, 720, 30.0 /* TODO: dependencies */)
                )
            )
        ),
        SampleVp9Packet(
            "Middle of keyframe (SID = 0)",
            "906536b79f3077686098017b" +
                "bede0002" + "3202168751210800" +
                // I=1,P=0,L=1,F=0,B=0,E=0,V=0,Z=0
                "a0" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=0,D=0
                "00" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "1989b602dbf8c9bf071d00b3e3ccf1135bd14a",
            isStartOfFrame = false,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 0,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "End of keyframe (SID = 0)",
            "906536bb9f3077686098017b" +
                "bede0002" + "32021ba651210c00" +
                // I=1,P=0,L=1,F=0,B=0,E=1,V=0,Z=0
                "a4" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=0,D=0
                "00" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "65019dea53fb1ec89768361a113523c6da88c9",
            isStartOfFrame = false,
            isEndOfFrame = true,
            isEndOfPicture = false,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 0,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "Beginning of SID=1 in keyframe picture",
            "906536bc9f3077686098017b" +
                "bede0002" + "32021ba651210d00" +
                // I=1,P=0,L=1,F=0,B=1,E=0,V=0,Z=0
                "a8" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=1,D=1
                "03" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "8702020004fe02cf04ff02cf9121c120e0c81080",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = false,
            isInterPicturePredicted = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 1,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = true,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "Middle of SID=1 in keyframe picture",
            "906536c09f3077686098017b" +
                "bede0002" + "320221cb51211100" +
                // I=1,P=0,L=1,F=0,B=0,E=0,V=0,Z=0
                "a0" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=1,D=1
                "03" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "08d6c732b4351ba6a440acb18ae6c444d02f89e2",
            isStartOfFrame = false,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isInterPicturePredicted = false,
            isKeyframe = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 1,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = true,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "End of SID=1 in keyframe picture",
            "906536c39f3077686098017b" +
                "bede0002" + "320227f051211400" +
                // I=1,P=0,L=1,F=0,B=0,E=1,V=0,Z=0
                "a4" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=1,D=1
                "03" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "2993d7ab77552b9ff92cb2f64cfd3dc9f81c8db7",
            isStartOfFrame = false,
            isEndOfFrame = true,
            isEndOfPicture = false,
            isKeyframe = false,
            isInterPicturePredicted = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 1,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = true,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "Beginning of SID=2 in keyframe picture",
            "906536c49f3077686098017b" +
                "bede0002" + "320227f051211500" +
                // I=1,P=0,L=1,F=0,B=1,E=0,V=0,Z=1
                "a9" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=2,D=1
                "05" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "8704242009fe059ec48704838320830089007fbc",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = false,
            isInterPicturePredicted = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = 0,
            sid = 2,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = true,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "End of SID=2 in keyframe picture -- end of picture",
            "90e536dd9f3077686098017b" +
                "bede0002" + "32025a1d51212e00" +
                // I=1,P=0,L=1,F=0,B=0,E=1,V=0,Z=1
                "a5" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=2,D=1
                "05" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "646f37516a6889fea39038cbc29dcde9702e8495",
            isStartOfFrame = false,
            isEndOfFrame = true,
            isEndOfPicture = true,
            isKeyframe = false,
            isInterPicturePredicted = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = 0,
            sid = 2,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = true,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "Complete SID=0,TID=1 K-SVC frame",
            "906536de9f3081f46098017b" +
                "bede0002" + "32025a1d51212f00" +
                // I=1,P=1,L=1,F=0,B=1,E=1,V=0,Z=1
                "ed" +
                // M=1,PID=0x1e66=7782
                "9e66" +
                // TID=2,U=1,SID=0,D=0
                "50" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "87080060027e016704ff02cfc487048383208000",
            isStartOfFrame = true,
            isEndOfFrame = true,
            isEndOfPicture = false,
            isKeyframe = false,
            isInterPicturePredicted = true,
            pictureId = 7782,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = 2,
            sid = 0,
            isSwitchingUpPoint = true,
            usesInterLayerDependency = false,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "Complete SID=1,TID=1 K-SVC frame",
            "906536df9f3081f46098017b" +
                "bede0002" + "32025a1d51213000" +
                // I=1,P=1,L=1,F=0,B=1,E=1,V=0,Z=1
                "ed" +
                // M=1,PID=0x1e66=7782
                "9e66" +
                // TID=2,U=1,SID=1,D=0
                "52" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "8710228004fe02cf04ff02cf9211c120e0cab080",
            isStartOfFrame = true,
            isEndOfFrame = true,
            isEndOfPicture = false,
            isKeyframe = false,
            isInterPicturePredicted = true,
            pictureId = 7782,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = 2,
            sid = 1,
            isSwitchingUpPoint = true,
            usesInterLayerDependency = false,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),
        SampleVp9Packet(
            "Complete SID=2,TID=1 K-SVC frame",
            "90e536e09f3081f46098017b" +
                "bede0002" + "32025a1d51213100" +
                // I=1,P=1,L=1,F=0,B=1,E=1,V=0,Z=1
                "ed" +
                // M=1,PID=0x1e66=7782
                "9e66" +
                // TID=2,U=1,SID=2,D=0
                "54" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // VP9 media.  Truncated.
                "8700444009fe059ec8c70483832b83001c007e20",
            isStartOfFrame = true,
            isEndOfFrame = true,
            isEndOfPicture = true,
            isKeyframe = false,
            isInterPicturePredicted = true,
            pictureId = 7782,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = 2,
            sid = 2,
            isSwitchingUpPoint = true,
            usesInterLayerDependency = false,
            tL0PICIDX = 253,
            descriptorSize = 5
        ),

        /* Window capture - Chrome 81 */
        SampleVp9Packet(
            "Beginning of window capture keyframe - contains SS, 1 layer",
            "90656dc9440dac37184b0cc4" +
                "bede0002" + "326bcdd351000100" +
                // I=1,P=0,L=0,F=0,B=1,E=0,V=1,Z=1
                "8b" +
                // M=1,PID=0x2558=9560
                "a558" +
                // Begin SS: N_S=0,Y=1,G=1
                "18" +
                // WIDTH=1576
                "0628" +
                // HEIGHT=1158
                "0486" +
                // N_G=1
                "01" +
                // TID=0,U=0,R=1
                "04" +
                // P_DIFF=1
                "01" +
                // VP9 media.  Truncated.
                "8249834200627048547638241c19a01803105f85",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 9560,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = null,
            sid = null,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = null,
            descriptorSize = 11,
            scalabilityStructure = RtpEncodingDesc(
                0x184b0cc4L,
                arrayOf(
                    RtpLayerDesc(0, 0, 0, 1158, 30.0)
                )
            )
        ),
        SampleVp9Packet(
            "Middle of window capture keyframe",
            "90656dca440dac37184b0cc4" +
                "bede0002" + "326bd1ec51000200" +
                // I=1,P=0,L=0,F=0,B=0,E=0,V=0,Z=1
                "81" +
                // M=1,PID=0x2558=9560
                "a558" +
                // VP9 media.  Truncated.
                "9089bebb979590638f183ac76bc650cde64144de",
            isStartOfFrame = false,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 9560,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = null,
            sid = null,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = null,
            descriptorSize = 3
        ),
        SampleVp9Packet(
            "End of window capture keyframe",
            "90e56de4440dac37184b0cc4" +
                "bede0002" + "326cf8d551001c00" +
                // I=1,P=0,L=0,F=0,B=0,E=1,V=0,Z=1
                "85" +
                // M=1,PID=0x2558=9560
                "a558" +
                // VP9 media.  Truncated.
                "7da09c7d64214a918874bb6b9cde16ef67545921",
            isStartOfFrame = false,
            isEndOfFrame = true,
            isEndOfPicture = true,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 9560,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = null,
            sid = null,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = null,
            descriptorSize = 3
        ),
        SampleVp9Packet(
            "Complete window capture non-keyframe",
            "90656e01440eb32f184b0cc4" +
                "bede0002" + "326e77cf51003900" +
                // I=1,P=1,L=0,F=0,B=1,E=1,V=0,Z=1
                "c9" +
                // M=1,PID=0x255A=9562
                "a55a" +
                // VP9 media.  Truncated.
                "8600409218fc5a0330101c060301f8c9e03049a2",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = false,
            isInterPicturePredicted = true,
            pictureId = 9562,
            hasExtendedPictureId = true,
            isUpperLevelReference = false,
            tid = null,
            sid = null,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = null,
            descriptorSize = 3
        ),

        /* Live video - Firefox 75 */
        SampleVp9Packet(
            "Beginning of Firefox 75 keyframe - no scalability",
            "9065385f33e8e7666538459e" +
                "bede0001" + "32a4e45a" +
                // I=1,P=0,L=0,F=0,B=1,E=0,V=1,Z=0
                "8a" +
                // M=1,PID=0x5bd8=23512
                "dbd8" +
                // Begin SS: N_S=0,Y=1,G=1
                "18" +
                // WIDTH=1280
                "0500" +
                // HEIGHT=720
                "02d0" +
                // N_G=1
                "01" +
                // TID=0,U=0,R=1
                "04" +
                // P_DIFF=1
                "01" +
                // VP9 media.  Truncated.
                "83498342004ff02cf050e0907063486011805fcf",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 23512,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = null,
            sid = null,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = null,
            descriptorSize = 11,
            scalabilityStructure = RtpEncodingDesc(
                0x6538459eL,
                arrayOf(
                    RtpLayerDesc(0, 0, 0, 720, 30.0)
                )
            )
        ),

        /* Live video - Firefox 77 Nightly */
        SampleVp9Packet(
            "Beginning of Firefox 77 keyframe - only temporal scalability",
            "90656563e256bc64a4d04528" +
                "bede0001" + "329c676d" +
                // I=1,P=0,L=1,F=0,B=1,E=0,V=1,Z=0
                "aa" +
                // M=1,PID=0x653e=25918
                "e53e" +
                // TID=0,U=0,SID=0,D=0
                "00" +
                // TL0PICIDX=0x5b=91
                "5b" +
                // Begin SS: N_S=0,Y=1,G=1
                "18" +
                // WIDTH=1280
                "0500" +
                // HEIGHT=720
                "02d0" +
                // N_G=4
                "04" +
                // TID=0,U=0,R=1
                "04" +
                // P_DIFF=4
                "04" +
                // TID=2,U=1,R=1
                "54" +
                // P_DIFF=1
                "01" +
                // TID=1,U=1,R=1
                "34" +
                // P_DIFF=2
                "02" +
                // TID=2,U=0,R=2
                "48" +
                // P_DIFF=1
                "01" +
                // P_DIFF=2
                "02" +
                // VP9 media.  Truncated.
                "83498342004ff02cf098e0907064a0600f005fcb",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = true,
            isInterPicturePredicted = false,
            pictureId = 25918,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 0,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = false,
            tL0PICIDX = 91,
            descriptorSize = 20,
            scalabilityStructure = RtpEncodingDesc(
                0xa4d04528L,
                arrayOf(
                    RtpLayerDesc(0, 0, 0, 720, 7.5),
                    RtpLayerDesc(0, 1, 0, 720, 15.0),
                    RtpLayerDesc(0, 2, 0, 720, 30.0)
                )
            )
        )
    )

    /*
        Template:
        ,
        SampleVp9Packet("Description",
            "",
            isStartOfFrame = true,
            isEndOfFrame = false,
            isEndOfPicture = false,
            isKeyframe = false,
            pictureId = 7781,
            hasExtendedPictureId = true,
            isUpperLevelReference = true,
            tid = 0,
            sid = 1,
            isSwitchingUpPoint = false,
            usesInterLayerDependency = true,
            tL0PICIDX = 253,
            descriptorSize = 5)
     */

    init {
        context("VP9 packets") {
            should("be parsed correctly") {
                for (t in testPackets) {
                    withClue(t.description) {
                        val p = Vp9Packet(t.data, 0, t.data.size)
                        p.isStartOfFrame shouldBe t.isStartOfFrame
                        p.isEndOfFrame shouldBe t.isEndOfFrame
                        p.isEndOfPicture shouldBe t.isEndOfPicture
                        p.isKeyframe shouldBe t.isKeyframe
                        p.isInterPicturePredicted shouldBe t.isInterPicturePredicted
                        if (t.pictureId != null) {
                            p.hasPictureId shouldBe true
                            p.pictureId shouldBe t.pictureId
                        } else {
                            p.hasPictureId shouldBe false
                        }
                        p.hasExtendedPictureId shouldBe t.hasExtendedPictureId
                        p.isUpperLevelReference shouldBe t.isUpperLevelReference
                        if (t.tid != null) {
                            p.hasLayerIndices shouldBe true
                            p.temporalLayerIndex shouldBe t.tid
                        } else {
                            p.hasLayerIndices shouldBe false
                        }
                        if (t.sid != null) {
                            p.hasLayerIndices shouldBe true
                            p.spatialLayerIndex shouldBe t.sid
                        } else {
                            p.hasLayerIndices shouldBe false
                        }
                        if (t.tL0PICIDX != null) {
                            p.hasTL0PICIDX shouldBe true
                            p.TL0PICIDX shouldBe t.tL0PICIDX
                        } else {
                            p.hasTL0PICIDX shouldBe false
                        }
                        p.isSwitchingUpPoint shouldBe t.isSwitchingUpPoint
                        p.usesInterLayerDependency shouldBe t.usesInterLayerDependency
                        if (t.scalabilityStructure != null) {
                            val tss = t.scalabilityStructure
                            p.hasScalabilityStructure shouldBe true
                            val ss = p.getScalabilityStructure()
                            ss shouldNotBe null
                            ss!!.primarySSRC shouldBe tss.primarySSRC
                            ss.layers.size shouldBe tss.layers.size
                            for ((index, layer) in ss.layers.withIndex()) {
                                val tLayer = tss.layers[index]
                                layer.layerId shouldBe tLayer.layerId
                                layer.index shouldBe tLayer.index
                                layer.sid shouldBe tLayer.sid
                                layer.tid shouldBe tLayer.tid
                                layer.height shouldBe tLayer.height
                                layer.frameRate shouldBe tLayer.frameRate
                                /* TODO: dependency layers */
                            }
                        } else {
                            p.hasScalabilityStructure shouldBe false
                            p.getScalabilityStructure() shouldBe null
                        }

                        val descSz =
                            DePacketizer.VP9PayloadDescriptor.getSize(p.buffer, p.payloadOffset, p.payloadLength)
                        descSz shouldBe t.descriptorSize
                    }
                }
            }
        }
    }
}

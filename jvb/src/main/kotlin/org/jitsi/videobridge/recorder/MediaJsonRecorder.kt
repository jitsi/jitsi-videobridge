package org.jitsi.videobridge.recorder

import org.bouncycastle.util.encoders.Base64
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger

class MediaJsonRecorder {
    val mkaRecorder = MkaRecorder()
    val logger = createLogger()

    fun handleEvent(event: Event) {
        when(event) {
            is StartEvent -> {
                logger.info("Start new stream: $event")
                mkaRecorder.startTrack(event.start.tag)
            }
            is MediaEvent -> {
                mkaRecorder.addFrame(
                    event.media.tag,
                    event.media.timestamp.toLong(),
                    Base64.decode(event.media.payload)
                )
            }
        }
    }

    fun stop() {
        logger.info("Stopping.")
        mkaRecorder.close()
    }
}
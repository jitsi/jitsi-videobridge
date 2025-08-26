/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge

import org.jitsi.nlj.DebugStateMode
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.VideoType
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.utils.NEVER
import org.jitsi.utils.event.EventEmitter
import org.jitsi.utils.event.SyncEventEmitter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.queue.PacketQueue
import org.jitsi.videobridge.cc.allocation.MediaSourceContainer
import org.jitsi.videobridge.cc.allocation.ReceiverConstraintsMap
import org.jitsi.videobridge.cc.allocation.VideoConstraints
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.util.TaskPools
import org.json.simple.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an endpoint in a conference (i.e. the entity associated with
 * a participant in the conference, which connects the participant's audio
 * and video channel). This might be an endpoint connected to this instance of
 * jitsi-videobridge, or a relayed endpoint connected to another bridge in the
 * same conference.
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
abstract class AbstractEndpoint protected constructor(
    /**
     * The [Conference] which this endpoint is to be part of.
     */
    val conference: Conference,
    /**
     * The ID of the endpoint.
     */
    final override val id: String,
    parentLogger: Logger
) : MediaSourceContainer {

    /**
     * The [Logger] used by the [AbstractEndpoint] class to print debug
     * information.
     */
    protected val logger: Logger = parentLogger.createChildLogger(this.javaClass.name, mapOf("epId" to id))

    /**
     * The map of source name -> ReceiverConstraintsMap.
     */
    private val receiverVideoConstraints = ConcurrentHashMap<String, ReceiverConstraintsMap>()

    private data class SourceAndHeight(
        val sourceName: String,
        val maxHeight: Int
    )

    private val receiverVideoConstraintsQueue = PacketQueue<SourceAndHeight>(
        128,
        null,
        "receiver-constraints-$id",
        { (sourceName, newMaxHeight) ->
            receiverVideoConstraintsChanged(sourceName, newMaxHeight)
            true
        },
        TaskPools.IO_POOL
    )

    /**
     * The statistics id of this <tt>Endpoint</tt>.
     */
    var statsId: String? = null
        set(value) {
            field = value
            if (value != null) {
                logger.addContext("stats_id", value)
            }
        }

    /**
     * The indicator which determines whether [.expire] has been called
     * on this <tt>Endpoint</tt>.
     */
    var isExpired = false
        private set

    /**
     * A map of source name -> VideoConstraints.
     *
     * Stores the maximum set of constraints applied by all receivers for each media source sent by this endpoint.
     * The client needs to send _at least_ this for a media source to satisfy all its receivers.
     */
    protected var maxReceiverVideoConstraints = mutableMapOf<String, VideoConstraints>()

    protected val eventEmitter: EventEmitter<EventHandler> = SyncEventEmitter()

    private val videoTypeCache = mutableMapOf<String, VideoType>()

    fun hasVideoAvailable(): Boolean {
        for (source in mediaSources) {
            if (source.videoType !== VideoType.NONE) {
                return true
            }
        }
        return videoTypeCache.values.any { it !== VideoType.NONE }
    }

    fun setVideoType(sourceName: String, videoType: VideoType) {
        val mediaSourceDesc = findMediaSourceDesc(sourceName)
        if (mediaSourceDesc != null) {
            if (mediaSourceDesc.videoType !== videoType) {
                if (mediaSourceDesc.videoType.isEnabled() && videoType.isEnabled()) {
                    logger.warn(
                        "Changing video type from ${mediaSourceDesc.videoType} to $videoType for $sourceName. " +
                            "This will not trigger re-signaling the mapping."
                    )
                }
                mediaSourceDesc.videoType = videoType
                conference.speechActivity.endpointVideoAvailabilityChanged()
            }
        }
        videoTypeCache[sourceName] = videoType
    }

    protected fun applyVideoTypeCache(mediaSourceDescs: Array<MediaSourceDesc>) {
        // Video types are signaled over JVB data channel while MediaStreamDesc over Colibri. The two channels need
        // to be synchronized.
        for (mediaSourceDesc in mediaSourceDescs) {
            val videoType = videoTypeCache[mediaSourceDesc.sourceName]
            if (videoType != null) {
                mediaSourceDesc.videoType = videoType
                videoTypeCache.remove(mediaSourceDesc.sourceName)
            }
        }
    }

    /**
     * Checks whether a specific SSRC belongs to this endpoint.
     * @param ssrc
     * @return
     */
    abstract fun receivesSsrc(ssrc: Long): Boolean

    /**
     * The set of SSRCs received from this endpoint.
     * @return
     */
    abstract val ssrcs: Set<Long>

    /**
     * The [AbstractEndpointMessageTransport] associated with
     * this endpoint.
     */
    open val messageTransport: AbstractEndpointMessageTransport?
        get() = null

    /** Whether this endpoint represents a visitor. */
    abstract val visitor: Boolean

    /**
     * Gets the description of the video [MediaSourceDesc] that this endpoint has advertised, or `null` if
     * it hasn't advertised any video sources.
     */
    protected val mediaSource: MediaSourceDesc?
        get() = mediaSources.firstOrNull()

    fun findMediaSourceDesc(sourceName: String): MediaSourceDesc? = mediaSources.firstOrNull {
        sourceName == it.sourceName
    }

    fun addEventHandler(eventHandler: EventHandler) {
        eventEmitter.addHandler(eventHandler)
    }

    fun removeEventHandler(eventHandler: EventHandler) {
        eventEmitter.removeHandler(eventHandler)
    }

    override fun toString() = "${javaClass.name} $id"

    /**
     * Expires this [AbstractEndpoint].
     */
    open fun expire() {
        logger.debug("Expiring.")
        isExpired = true
        conference.endpointExpired(this)
    }

    /**
     * Return true if this endpoint should expire (based on whatever logic is
     * appropriate for that endpoint implementation.
     *
     * @return true if this endpoint should expire, false otherwise
     */
    abstract fun shouldExpire(): Boolean

    /**
     * Get the last 'incoming activity' (packets received) this endpoint has seen
     * @return the timestamp, in milliseconds, of the last activity of this endpoint
     */
    open val lastIncomingActivity: Instant?
        get() = NEVER

    /**
     * Requests a keyframe from this endpoint for the specified media SSRC.
     *
     * @param mediaSsrc the media SSRC to request a keyframe from.
     */
    abstract fun requestKeyframe(mediaSsrc: Long)

    /**
     * Requests a keyframe from this endpoint on the first video SSRC
     * it finds.  Being able to request a  keyframe without passing a specific
     * SSRC is useful for things like requesting a pre-emptive keyframes when a new
     * active speaker is detected (where it isn't convenient to try and look up
     * a particular SSRC).
     */
    abstract fun requestKeyframe()

    /** A JSON representation of the parts of this object's state that are deemed useful for debugging. */
    open fun debugState(mode: DebugStateMode): JSONObject = JSONObject().apply {
        val receiverVideoConstraints = JSONObject()
        this@AbstractEndpoint.receiverVideoConstraints.forEach { (sourceName, receiverConstraints) ->
            receiverVideoConstraints[sourceName] = receiverConstraints.getDebugState()
        }
        this["receiver_video_constraints"] = receiverVideoConstraints
        this["max_receiver_video_constraints"] = HashMap(maxReceiverVideoConstraints)
        this["expired"] = isExpired
        this["stats_id"] = statsId
    }

    /**
     * Enqueues a call to send a receiver video constrains message.
     * We use a queue to prevent races in sending these constraint messages.
     */
    private fun sendReceiverVideoConstraintsChanged(sourceName: String, newMaxHeight: Int) {
        receiverVideoConstraintsQueue.add(SourceAndHeight(sourceName, newMaxHeight))
    }

    /**
     * Computes and sets the [.maxReceiverVideoConstraints] from the specified video constraints of the media
     * source identified by the given source name.
     *
     * @param sourceName the name of the media source for which the constraints have changed.
     * @param newMaxHeight the maximum height resulting from the current set of constraints.
     * (Currently we only support constraining the height, and not frame rate.)
     */
    private fun receiverVideoConstraintsChanged(sourceName: String, newMaxHeight: Int) {
        val oldReceiverMaxVideoConstraints = maxReceiverVideoConstraints[sourceName]
        val newReceiverMaxVideoConstraints = VideoConstraints(newMaxHeight, -1.0)
        if (newReceiverMaxVideoConstraints != oldReceiverMaxVideoConstraints) {
            maxReceiverVideoConstraints[sourceName] = newReceiverMaxVideoConstraints
            sendVideoConstraints(sourceName, newReceiverMaxVideoConstraints)
        }
    }

    /**
     * Whether the remote endpoint is currently sending (non-silence) audio.
     */
    abstract val isSendingAudio: Boolean

    /**
     * Whether the remote endpoint is currently sending video.
     */
    abstract val isSendingVideo: Boolean

    /**
     * The set of [AudioSourceDesc]s that this endpoint has advertised.
     */
    abstract var audioSources: List<AudioSourceDesc>

    /**
     * Adds a payload type to this endpoint.
     */
    abstract fun addPayloadType(payloadType: PayloadType)

    /**
     * Adds an RTP extension to this endpoint
     */
    abstract fun addRtpExtension(rtpExtension: RtpExtension)

    /**
     * Sets extmap-allow-mixed for this endpoint
     */
    abstract fun setExtmapAllowMixed(allow: Boolean)

    /**
     * Notifies this instance that the max video constraints that the bridge needs to receive from a source of this
     * endpoint has changed. Each implementation handles this notification differently.
     *
     * @param sourceName the name of the media source
     * @param maxVideoConstraints the max video constraints that the bridge needs to receive from the source
     */
    protected abstract fun sendVideoConstraints(sourceName: String, maxVideoConstraints: VideoConstraints)

    /**
     * Notifies this instance that a specified received wants to receive the specified video constraints from the media
     * source with the given source name.
     *
     * The receiver can be either another endpoint, or a remote bridge.
     *
     * @param receiverId the id that specifies the receiver endpoint.
     * @param sourceName the name of the media source for which the constraints are to be applied.
     * @param newVideoConstraints the video constraints that the receiver wishes to receive.
     */
    fun addReceiver(receiverId: String, sourceName: String, newVideoConstraints: VideoConstraints) {
        val sourceConstraints = receiverVideoConstraints.computeIfAbsent(sourceName) { ReceiverConstraintsMap() }
        synchronized(sourceConstraints) {
            val oldVideoConstraints = sourceConstraints.put(receiverId, newVideoConstraints)
            if (oldVideoConstraints == null || oldVideoConstraints != newVideoConstraints) {
                logger.debug {
                    "Changed receiver constraints: $receiverId->$sourceName: ${newVideoConstraints.maxHeight}"
                }
                sendReceiverVideoConstraintsChanged(sourceName, sourceConstraints.maxHeight)
            }
        }
    }

    /** Notify this endpoint that another endpoint expired */
    open fun otherEndpointExpired(expired: AbstractEndpoint) {
        removeReceiver(expired.id)
    }

    /**
     * Notifies this instance that the specified receiver no longer wants or
     * needs to receive anything from the endpoint attached to this
     * instance (the sender).
     *
     * @param receiverId the id that specifies the receiver endpoint
     */
    fun removeReceiver(receiverId: String) {
        for ((sourceName, sourceConstraints) in receiverVideoConstraints) {
            synchronized(sourceConstraints) {
                if (sourceConstraints.remove(receiverId) != null) {
                    logger.debug { "Removed receiver $receiverId for $sourceName" }
                    sendReceiverVideoConstraintsChanged(sourceName, sourceConstraints.maxHeight)
                }
            }
        }
    }

    /**
     * Notifies this instance that the specified receiver no longer wants or needs to receive anything from the media
     * source attached to this instance (the sender).
     *
     * @param receiverId the id that specifies the receiver endpoint
     * @param sourceName the media source name
     */
    fun removeSourceReceiver(receiverId: String, sourceName: String) {
        val sourceConstraints = receiverVideoConstraints[sourceName]
        if (sourceConstraints != null) {
            synchronized(sourceConstraints) {
                if (sourceConstraints.remove(receiverId) != null) {
                    logger.debug { "Removed receiver $receiverId for $sourceName" }
                    sendReceiverVideoConstraintsChanged(sourceName, sourceConstraints.maxHeight)
                }
            }
        }
    }

    interface EventHandler {
        fun sourcesChanged()
    }
}

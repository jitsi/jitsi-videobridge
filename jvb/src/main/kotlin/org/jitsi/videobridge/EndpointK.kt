/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.nlj.util.NEVER
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.shim.ChannelShim
import java.time.Clock
import java.time.Duration

class EndpointK @JvmOverloads constructor(
    id: String,
    conference: Conference,
    parentLogger: Logger,
    iceControlling: Boolean,
    clock: Clock = Clock.systemUTC()
) : Endpoint(id, conference, parentLogger, iceControlling, clock) {

    /**
     * Previously, an endpoint expired when all of its channels did.  Channels
     * now only exist in their 'shim' form for backwards compatibility, so to
     * find out whether or not the endpoint expired, we'll check the activity
     * timestamps from the transceiver and use the largest of the expire times
     * set in the channel shims.
     */
    override fun shouldExpire(): Boolean {
        if (iceTransport.hasFailed()) {
            logger.warn("Allowing to expire because ICE failed.")
            return true
        }

        val maxExpireTimeFromChannelShims = channelShims
            .map(ChannelShim::getExpire)
            .map { Duration.ofSeconds(it.toLong()) }
            .max() ?: Duration.ZERO

        val lastActivity = lastIncomingActivity
        val now = clock.instant()

        if (lastActivity == NEVER) {
            val timeSinceCreation = Duration.between(creationTime, now)
            if (timeSinceCreation > EP_TIMEOUT) {
                logger.info(
                    "Endpoint's ICE connection has neither failed nor connected " +
                        "after " + timeSinceCreation + ", expiring"
                )
                return true
            }
            // We haven't seen any activity yet. If this continues ICE will
            // eventually fail (which is handled above).
        }
        if (Duration.between(lastActivity, now) > maxExpireTimeFromChannelShims) {
            logger.info("Allowing to expire because of no activity in over $maxExpireTimeFromChannelShims")
            return true
        }
        return false
    }

    /**
     * Set the local SSRC for [mediaType] to [ssrc] for this endpoint.
     */
    override fun setLocalSsrc(mediaType: MediaType, ssrc: Long) = transceiver.setLocalSsrc(mediaType, ssrc)
}

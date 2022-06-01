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

package org.jitsi.nlj

interface AudioLevelListener {
    /**
     * Process the audio level from a received audio RTP packet.
     *
     * @param sourceSsrc The SSRC identifying the source of the audio stream.
     * @param level The audio level.
     * @return A boolean set to {@code true} if this packet should be
     * discarded without being forwarded to other endpoints.
     */
    fun onLevelReceived(sourceSsrc: Long, level: Long): Boolean
}

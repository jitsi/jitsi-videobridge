/*
 * Copyright @ 2020 - present 8x8, Inc.
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
package org.jitsi.videobridge.cc.allocation

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.json.simple.JSONObject

@JsonIgnoreProperties(ignoreUnknown = true)
data class VideoConstraints @JvmOverloads constructor(
    val maxHeight: Int,
    val maxFrameRate: Double = UNLIMITED_FRAME_RATE
) {
    init {
        require(maxHeight == UNLIMITED_HEIGHT || maxHeight >= 0) { "maxHeight must be either -1, 0, or positive." }
        require(maxFrameRate == UNLIMITED_FRAME_RATE || maxFrameRate >= 0.0) {
            "maxFrameRate must be either -1, or >= 0"
        }
    }
    override fun toString(): String = JSONObject().apply {
        this["maxHeight"] = maxHeight
        this["maxFrameRate"] = maxFrameRate
    }.toJSONString()

    fun heightIsLimited() = maxHeight != UNLIMITED_HEIGHT
    fun frameRateIsLimited() = maxFrameRate != UNLIMITED_FRAME_RATE
    fun isDisabled() = maxHeight == 0 || maxFrameRate == 0.0

    companion object {
        const val UNLIMITED_HEIGHT = -1
        const val UNLIMITED_FRAME_RATE = -1.0
        val NOTHING = VideoConstraints(0)
        val UNLIMITED = VideoConstraints(UNLIMITED_HEIGHT, UNLIMITED_FRAME_RATE)
    }
}

fun Map<String, VideoConstraints>.prettyPrint(): String = entries.joinToString { "${it.key}->${it.value.maxHeight}" }

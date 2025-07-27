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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.from
import java.util.function.Supplier

/**
 * A JVB-wide audio last-n value which will be observed by all endpoints on this bridge.
 *
 * A value of -1 means no limit is enforced.
 */
class JvbAudioLastN : Supplier<Int> {
    private val defaultJvbAudioLastN: Int by config("videobridge.cc.jvb-audio-last-n".from(JitsiConfig.newConfig))
    var jvbAudioLastN: Int = defaultJvbAudioLastN

    override fun get(): Int = jvbAudioLastN
}

@JvmField
val jvbAudioLastNSingleton: JvbAudioLastN = JvbAudioLastN()

fun calculateAudioLastN(vararg audioLastN: Int): Int {
    val min = audioLastN.map { if (it == -1) Int.MAX_VALUE else it }.minOrNull() ?: Int.MAX_VALUE
    return if (min == Int.MAX_VALUE) -1 else min
} 
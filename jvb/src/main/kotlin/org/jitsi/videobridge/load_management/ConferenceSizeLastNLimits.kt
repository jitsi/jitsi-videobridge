/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.videobridge.load_management

import com.typesafe.config.ConfigObject
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import java.util.TreeMap

class ConferenceSizeLastNLimits {
    private val lastNLimits: TreeMap<Int, Int> by config {
        "videobridge.load-management.conference-last-n-limits".from(JitsiConfig.newConfig)
            .convertFrom<ConfigObject> { cfg ->
                TreeMap(cfg.entries.map { it.key.toInt() to it.value.unwrapped() as Int }.toMap())
            }
    }

    fun getLastNLimit(conferenceSize: Int): Int = lastNLimits.floorEntry(conferenceSize)?.value ?: -1

    companion object {
        val singleton = ConferenceSizeLastNLimits()
    }
}

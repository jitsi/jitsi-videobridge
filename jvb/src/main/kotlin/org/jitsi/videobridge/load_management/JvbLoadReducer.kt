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

package org.jitsi.videobridge.load_management

import org.jitsi.nlj.util.OrderedJsonObject
import java.time.Duration

interface JvbLoadReducer {
    fun reduceLoad()
    fun recover()

    /**
     * How long to wait for load reduction / recovery operations to take effect
     * before doing another iteration.
     */
    fun impactTime(): Duration

    fun getStats(): OrderedJsonObject

    companion object {
        const val CONFIG_BASE = "videobridge.load-management.load-reducers"
    }
}

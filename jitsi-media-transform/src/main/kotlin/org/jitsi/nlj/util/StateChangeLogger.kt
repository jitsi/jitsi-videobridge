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

package org.jitsi.nlj.util

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cinfo

/**
 * Class that logs when state first becomes false, or changes from false to true,
 * without logging on every packet.
 */
class StateChangeLogger(
    val desc: String,
    val logger: Logger
) {
    var state: Boolean? = null

    fun setState(newState: Boolean, instance: Any, instanceDesc: () -> String) {
        if (state != newState) {
            if (newState == false) {
                logger.cinfo { "Packet $instance has $desc.  ${instanceDesc()}" }
            } else if (state != null) {
                logger.cinfo { "Packet $instance source no longer has $desc.  ${instanceDesc()}" }
            }
            state = newState
        }
    }
}

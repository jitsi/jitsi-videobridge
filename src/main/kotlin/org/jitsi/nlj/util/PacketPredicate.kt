/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.rtp.Packet

typealias PacketPredicate = (Packet) -> Boolean

// Predicate which accepts all packets
val AllPackets = object : (Packet) -> Boolean {
    override fun invoke(p1: Packet): Boolean = true
}

fun StringBuffer.appendLnIndent(numSpaces: Int, msg: String) {
    append(" ".repeat(numSpaces)).appendln(msg)
}

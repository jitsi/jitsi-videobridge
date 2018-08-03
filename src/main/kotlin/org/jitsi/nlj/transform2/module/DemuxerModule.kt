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
package org.jitsi.nlj.transform2.module

import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.rtp.Packet
import kotlin.reflect.KClass

class DemuxerModule : Module("Demuxer") {
    private var transformPaths: MutableMap<PacketPredicate, ModuleChain> = mutableMapOf()
    private var tempFirstPath: ModuleChain? = null

    fun addPacketPath(pp: PacketPath) {
        transformPaths[pp.predicate] = pp.path
    }

    override fun attach(nextModule: (List<Packet>) -> Unit) {//= throw Exception()
    }

    override fun doProcessPackets(p: List<Packet>) {
        // Is this scheme always better? Or only when the list of
        // packets is above a certain size?
        transformPaths.forEach { predicate, chain ->
            val pathPackets = p.filter(predicate)
            next(chain, pathPackets)
        }
    }

    fun findFirst(moduleClass: KClass<*>): Module? {
        for (m in transformPaths.values) {
            val mod = m.findFirst(moduleClass)
            if (mod != null) { return mod }
        }
        return null
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            transformPaths.values.forEach {
                append(it.getStats(indent + 2))
            }
            toString()
        }
    }
}

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
package org.jitsi.nlj.transform.module

import org.jitsi.nlj.util.EvictingConcurrentQueue
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import kotlin.reflect.KClass
import kotlin.system.measureTimeMillis

class ModuleChain {
    val modules = mutableListOf<Module>()
    private val packetProcessingDurations = EvictingConcurrentQueue<Double>(100)
    private var name: String = ""

    fun name(n: String) {
        this.name = n
    }

    fun addModule(m: Module) {
        addAndConnect(m)
    }

    fun demux(b: DemuxerModule.() -> Unit) {
        val sm = DemuxerModule().apply(b)
        addAndConnect(sm)
    }

    fun mux(b: MuxerModule.() -> Unit) {
        val mm = MuxerModule().apply(b)
        addAndConnect(mm)
    }

    //TODO: trying this as an easy way to add a final output, but that means
    // should probably enforce that nothing else can be added after this
    // other option would be to force the user to implement a addModule to put
    // the packets somewhere.
    fun attach(handler: PacketHandler) {
        val previousModule = modules.lastOrNull()
        //println("Attaching handler to $previousModule")
        previousModule?.attach(handler)
    }

    private fun addAndConnect(m: Module) {
        val previousModule = modules.lastOrNull()
        modules.add(m)
        previousModule?.attach(m::processPackets)
    }

    fun processPackets(pkts: List<Packet>) {
        val time = measureTimeMillis {
            modules[0].processPackets(pkts)
        }
        packetProcessingDurations.add(time / pkts.size.toDouble() )
    }

    fun getStats(indent: Int = 0): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, name)
            appendLnIndent(indent, "Average time spent in this chain per packet: ${packetProcessingDurations.average()} ms")
            modules.forEach { append(it.getStats(indent + 2)) }
            toString()
        }
    }

    // Better than below? But can't be called by java... :/
    inline fun <reified T : Any>findFirst2() : T? {
        for (m in modules) {
            if (m::class == T::class) {
                return m as T
            } else if (m is DemuxerModule) {
                val nested = m.findFirst(T::class)
                if (nested != null) { return nested as T }
            }
        }
        return null
    }

    // TODO: can we make this cleaner to call from java? (don't take in a kclass)
    fun findFirst(moduleClass: KClass<*>): Module? {
        for (m in modules) {
            if (m::class == moduleClass) {
                return m
            } else if (m is DemuxerModule) {
                val nested = m.findFirst(moduleClass)
                if (nested != null) { return nested }
            }
        }
        return null
    }

    fun findAll(moduleClass: KClass<*>): List<Module> {
        return modules.filter { it -> it::class == moduleClass }
    }
}

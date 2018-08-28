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

import org.jitsi.rtp.Packet

// Maybe this should add a thread context boundary (all incoming packets written to a queue)
// and then use an executor to schedule the reading?  otherwise this module doesn't do
// much, a thread calls doProcessPackets which just invokes the next thing on the chain.
class MuxerModule : Module("MuxerModule") {
    override fun doProcessPackets(p: List<Packet>) {
        next(p)
    }

    fun attachInput(m: Module) {
        m.attach(this::processPackets)
    }

    fun attachInput(m: ModuleChain) {
        m.attach(this::processPackets)
    }
}

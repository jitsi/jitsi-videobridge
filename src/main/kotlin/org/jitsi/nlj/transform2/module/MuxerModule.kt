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

import org.jitsi.rtp.Packet

// I don't think we need any special handling in MuxerModule: we can just
// attach one muxer module to multiple other modules
// we'd just need to make sure we didn't get into any concurrency issues
// (so maybe muxer should enforce posting all jobs to be handled
// by its executor?) if not, do we need it at all?  the first module
// can just take all the inputs.  we could give each module an executor
// and then the modules don't need to be aware of the thread boundaries:
// they'd either be sharing an executor/thread or not but that would be handled
// at a layer above
class MuxerModule : Module("MuxerModule") {
    override fun doProcessPackets(p: List<Packet>) {
        next(p)
    }

    fun attachInput(m: Module) {
        m.attach(this::doProcessPackets)
    }

    fun attachInput(m: ModuleChain) {
        //TODO: need to add something so we don't have to reach into m.modules
        m.modules.last().attach(this::processPackets)
    }
}

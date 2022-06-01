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

import java.util.concurrent.CopyOnWriteArrayList
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer

typealias SsrcAssociationHandler = (SsrcAssociation) -> Unit

class SsrcAssociationStore(
    private val name: String = "SSRC Associations"
) : NodeStatsProducer {
    private val ssrcAssociations: MutableList<SsrcAssociation> = CopyOnWriteArrayList()
    /**
     * The SSRC associations indexed by the primary SSRC.  Since an SSRC may have
     * multiple secondary SSRC mappings, the primary SSRC maps to a list of its
     * SSRC associations
     */
    private var ssrcAssociationsByPrimarySsrc = mapOf<Long, List<SsrcAssociation>>()
    private var ssrcAssociationsBySecondarySsrc = mapOf<Long, SsrcAssociation>()

    private val handlers: MutableList<SsrcAssociationHandler> = CopyOnWriteArrayList()

    /**
     * Each time an association is added, we want to invoke the handlers
     * and each time a handler is added, want to invoke it with all existing
     * associations.  In order to make each of those operations a single,
     * atomic operation, we use this lock to synchronize them.
     */
    private val lock = Any()

    fun addAssociation(ssrcAssociation: SsrcAssociation) {
        synchronized(lock) {
            ssrcAssociations.add(ssrcAssociation)
            rebuildMaps()
            handlers.forEach { it(ssrcAssociation) }
        }
    }

    private fun rebuildMaps() {
        ssrcAssociationsByPrimarySsrc = ssrcAssociations.groupBy(SsrcAssociation::primarySsrc)
        ssrcAssociationsBySecondarySsrc = ssrcAssociations.associateBy(SsrcAssociation::secondarySsrc)
    }

    fun getPrimarySsrc(secondarySsrc: Long): Long? =
        ssrcAssociationsBySecondarySsrc[secondarySsrc]?.primarySsrc

    fun getSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? =
        ssrcAssociationsByPrimarySsrc[primarySsrc]?.find { it.type == associationType }?.secondarySsrc

    /**
     * When an SSRC has no associations at all (audio, for example), we consider it a
     * 'primary' SSRC.  So to perform this check we assume the given SSRC has been
     * signalled and simply verify that it's *not* signaled as a secondary SSRC.
     * Note that this may mean there is a slight window before the SSRC associations are
     * processed during which we return true for an SSRC which will later be denoted
     * as a secondary ssrc.
     */
    fun isPrimarySsrc(ssrc: Long): Boolean {
        return !ssrcAssociationsBySecondarySsrc.containsKey(ssrc)
    }

    fun onAssociation(handler: (SsrcAssociation) -> Unit) {
        synchronized(lock) {
            handlers.add(handler)
            ssrcAssociations.forEach(handler)
        }
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock(name).apply {
        addString("SSRC associations", ssrcAssociations.toString())
    }
}

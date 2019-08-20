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

import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import java.util.concurrent.CopyOnWriteArrayList

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

    fun addAssociation(ssrcAssociation: SsrcAssociation) {
        ssrcAssociations.add(ssrcAssociation)
        rebuildMaps()
    }

    private fun rebuildMaps() {
        ssrcAssociationsByPrimarySsrc = ssrcAssociations.groupBy(SsrcAssociation::primarySsrc)
        ssrcAssociationsBySecondarySsrc = ssrcAssociations.associateBy(SsrcAssociation::secondarySsrc)
    }

    fun getPrimarySsrc(secondarySsrc: Long): Long? = ssrcAssociationsBySecondarySsrc[secondarySsrc]?.primarySsrc

    fun getSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? =
        ssrcAssociationsByPrimarySsrc[primarySsrc]?.find { it.type == associationType }?.secondarySsrc

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock(name).apply {
        addString("SSRC associations", ssrcAssociations.toString())
    }
}
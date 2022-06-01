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

abstract class SsrcAssociation(
    val primarySsrc: Long,
    val secondarySsrc: Long,
    val type: SsrcAssociationType
) {
    override fun toString(): String = "$secondarySsrc -> $primarySsrc ($type)"
}

/**
 * A [LocalSsrcAssociation] is an SSRC association which belongs to
 * the endpoint itself; i.e. the processor of a [LocalSsrcAssociation]
 * is also the 'owner' of the SSRCs signaled in the association.
 */
class LocalSsrcAssociation(
    primarySsrc: Long,
    secondarySsrc: Long,
    type: SsrcAssociationType
) : SsrcAssociation(primarySsrc, secondarySsrc, type)

/**
 * A [RemoteSsrcAssociation] is an SSRC association which belongs to
 * another endpoint; i.e. the processor of a [RemoteSsrcAssociation]
 * does not 'own' the SSRCs signaled in the association.
 */
class RemoteSsrcAssociation(
    primarySsrc: Long,
    secondarySsrc: Long,
    type: SsrcAssociationType
) : SsrcAssociation(primarySsrc, secondarySsrc, type)

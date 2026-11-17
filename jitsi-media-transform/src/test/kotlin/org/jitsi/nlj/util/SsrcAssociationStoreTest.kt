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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.rtp.SsrcAssociationType

class SsrcAssociationStoreTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val store = SsrcAssociationStore()

    init {
        context("An association that is removed") {
            store.addAssociation(RemoteSsrcAssociation(1L, 2L, SsrcAssociationType.RTX))
            store.addAssociation(RemoteSsrcAssociation(3L, 4L, SsrcAssociationType.RTX))

            should("no longer be found, and leave the others") {
                // Another object with the same SSRCs and type.
                store.removeAssociation(RemoteSsrcAssociation(1L, 2L, SsrcAssociationType.RTX))

                store.getSecondarySsrc(1L, SsrcAssociationType.RTX) shouldBe null
                store.getPrimarySsrc(2L) shouldBe null
                store.isPrimarySsrc(2L) shouldBe true
                store.getSecondarySsrc(3L, SsrcAssociationType.RTX) shouldBe 4L
                store.getPrimarySsrc(4L) shouldBe 3L
            }
            should("not be given to a handler that is added later") {
                store.removeAssociation(RemoteSsrcAssociation(1L, 2L, SsrcAssociationType.RTX))

                val seen = mutableListOf<Long>()
                store.onAssociation { seen.add(it.primarySsrc) }
                seen shouldBe listOf(3L)
            }
            should("stay when the SSRCs or the type of the removal differ") {
                store.removeAssociation(RemoteSsrcAssociation(1L, 4L, SsrcAssociationType.RTX))
                store.removeAssociation(RemoteSsrcAssociation(1L, 2L, SsrcAssociationType.FEC))

                store.getSecondarySsrc(1L, SsrcAssociationType.RTX) shouldBe 2L
            }
        }
    }
}

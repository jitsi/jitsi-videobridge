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

package org.jitsi.nlj.codec.vpx

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan

internal class PictureIdIndexTrackerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val indexTracker = PictureIdIndexTracker()

    init {
        context("feeding in the first sequence number") {
            val firstIndex = indexTracker.update(32000)
            should("return itself as the index") {
                firstIndex shouldBe 32000
            }
            context("and then another without rolling over") {
                val secondIndex = indexTracker.update(32001)
                should("return itself as the index") {
                    secondIndex shouldBe 32001
                }
                context("and then another which does roll over") {
                    val rollOverIndex = indexTracker.update(2)
                    should("return the proper index") {
                        rollOverIndex shouldBe 1 /* roc */ * 0x8000 + 2
                    }
                    context("and then a sequence number from the previous rollover") {
                        val prevRollOverIndex = indexTracker.update(32002)
                        should("return the proper index") {
                            prevRollOverIndex shouldBe 32002
                        }
                    }
                }
                context("and then an older sequence number") {
                    val oldIndex = indexTracker.update(31000)
                    should("return the proper index") {
                        oldIndex shouldBe oldIndex
                    }
                }
            }
        }
        context("a series of sequence numbers") {
            should("never return a negative index") {
                var seqNum = 22134
                repeat(35537) {
                    indexTracker.update(seqNum++) shouldBeGreaterThan 0
                }
            }
        }
    }
}

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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

internal class IndexTrackerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        context("RtpSequenceIndexTracker") {
            val indexTracker = RtpSequenceIndexTracker()

            context("Not accept input outside the range") {
                listOf(-1, 0x1_0000, 0x1000_0000).forEach { invalid ->
                    withClue("Testing $invalid") {
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.update(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.interpret(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.resetAt(invalid)
                        }
                    }
                }
            }

            context("feeding in the first sequence number") {
                val firstIndex = indexTracker.update(65000)
                should("return itself as the index") {
                    firstIndex shouldBe 65000
                }
                context("and then another without rolling over") {
                    val secondIndex = indexTracker.update(65001)
                    should("return itself as the index") {
                        secondIndex shouldBe 65001
                    }
                    context("and then another which does roll over") {
                        val rollOverIndex = indexTracker.update(2)
                        should("return the proper index") {
                            rollOverIndex shouldBe 1 * 0x1_0000 + 2L
                        }
                        context("and then a sequence number from the previous rollover") {
                            val prevRollOverIndex = indexTracker.update(65002)
                            should("return the proper index") {
                                prevRollOverIndex shouldBe 65002
                            }
                        }
                    }
                    context("and then an older sequence number") {
                        val oldIndex = 64000
                        should("return the proper index") {
                            indexTracker.update(oldIndex) shouldBe oldIndex
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
            context("resetting the index tracker") {
                indexTracker.update(65000)
                indexTracker.update(65001)
                indexTracker.update(2)
                indexTracker.resetAt(65002)
                should("return the proper index") {
                    indexTracker.update(65002) shouldBe 2 * 0x1_0000 + 65002
                }
            }
        }
        context("LongIndexTracker") {
            // This should behave identically to the RtpSequenceIndexTracker, but we can't reuse the same test because
            // the API is different
            val indexTracker = LongIndexTracker(16)

            context("Not accept input outside the range") {
                listOf(-1L, 0x1_0000L, 0x1000_0000L).forEach { invalid ->
                    withClue("Testing $invalid") {
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.update(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.interpret(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.resetAt(invalid)
                        }
                    }
                }
            }

            context("feeding in the first sequence number") {
                val firstIndex = indexTracker.update(65000)
                should("return itself as the index") {
                    firstIndex shouldBe 65000
                }
                context("and then another without rolling over") {
                    val secondIndex = indexTracker.update(65001)
                    should("return itself as the index") {
                        secondIndex shouldBe 65001
                    }
                    context("and then another which does roll over") {
                        val rollOverIndex = indexTracker.update(2)
                        should("return the proper index") {
                            rollOverIndex shouldBe 1 * 0x1_0000 + 2L
                        }
                        context("and then a sequence number from the previous rollover") {
                            val prevRollOverIndex = indexTracker.update(65002)
                            should("return the proper index") {
                                prevRollOverIndex shouldBe 65002
                            }
                        }
                    }
                    context("and then an older sequence number") {
                        val oldIndex = 64000L
                        should("return the proper index") {
                            indexTracker.update(oldIndex) shouldBe oldIndex
                        }
                    }
                }
            }
            context("a series of sequence numbers") {
                should("never return a negative index") {
                    var seqNum = 22134L
                    repeat(35537) {
                        indexTracker.update(seqNum++) shouldBeGreaterThan 0
                    }
                }
            }
            context("resetting the index tracker") {
                indexTracker.update(65000)
                indexTracker.update(65001)
                indexTracker.update(2)
                indexTracker.resetAt(65002)
                should("return the proper index") {
                    indexTracker.update(65002) shouldBe 2 * 0x1_0000 + 65002
                }
            }
        }
        context("PictureIdIndexTracker") {
            val indexTracker = PictureIdIndexTracker()

            context("Not accept input outside the range") {
                listOf(-1, 0x8000, 0x1000_0000).forEach { invalid ->
                    withClue("Testing $invalid") {
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.update(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.interpret(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.resetAt(invalid)
                        }
                    }
                }
            }

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
                            rollOverIndex shouldBe 1L * 0x8000 + 2
                        }
                        context("and then a sequence number from the previous rollover") {
                            val prevRollOverIndex = indexTracker.update(32002)
                            should("return the proper index") {
                                prevRollOverIndex shouldBe 32002
                            }
                        }
                    }
                    context("and then an older sequence number") {
                        val oldIndex = 31000
                        should("return the proper index") {
                            indexTracker.update(oldIndex) shouldBe oldIndex
                        }
                    }
                }
            }
            context("a series of sequence numbers") {
                should("never return a negative index") {
                    var seqNum = 22134
                    repeat(3553) {
                        indexTracker.update(seqNum++) shouldBeGreaterThan 0
                    }
                }
            }
        }
        context("RtpTimestampIndexTracker") {
            val indexTracker = RtpTimestampIndexTracker()
            val base = 4000000000L // higher than 2^31

            context("Not accept input outside the range") {
                listOf(-1L, 0x1_0000_0000, 0x100_0000_0000).forEach { invalid ->
                    withClue("Testing $invalid") {
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.update(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.interpret(invalid)
                        }
                        shouldThrow<IllegalArgumentException> {
                            indexTracker.resetAt(invalid)
                        }
                    }
                }
            }

            context("feeding in the first timestamp") {
                val firstIndex = indexTracker.update(base)
                should("return itself as the index") {
                    firstIndex shouldBe base
                }
                context("and then another without rolling over") {
                    val secondIndex = indexTracker.update(base + 1000)
                    should("return itself as the index") {
                        secondIndex shouldBe (base + 1000)
                    }
                    context("and then another which does roll over") {
                        val rollOverIndex = indexTracker.update(2000L)
                        should("return the proper index") {
                            rollOverIndex shouldBe 0x1_0000_0000 + 2000L
                        }
                        context("and then a timestamp from the previous rollover") {
                            val prevRollOverIndex = indexTracker.update(base + 2000)
                            should("return the proper index") {
                                prevRollOverIndex shouldBe base + 2000
                            }
                        }
                    }
                    context("and then an older timestamp") {
                        val oldIndex = base - 1000000
                        should("return the proper index") {
                            indexTracker.update(oldIndex) shouldBe oldIndex
                        }
                    }
                }
            }
            context("a series of timestamps") {
                should("never return a negative index") {
                    var timestamp = 3000000000L
                    repeat(35537) {
                        indexTracker.update(timestamp++) shouldBeGreaterThan 0
                    }
                }
            }
            context("resetting the timestamp tracker") {
                indexTracker.update(base)
                indexTracker.update(base + 1000)
                indexTracker.update(2000L)
                indexTracker.resetAt(base + 2000)
                should("return the proper index") {
                    indexTracker.update(base + 2000) shouldBe 2L * 0x1_0000_0000 + base + 2000
                }
            }
        }
    }
}

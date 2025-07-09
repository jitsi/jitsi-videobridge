/*
 * Copyright @ 2025 - present 8x8, Inc.
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

package org.jitsi.videobridge

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.videobridge.message.ReceiverAudioSubscriptionMessage
import org.jitsi.videobridge.relay.AudioSourceDesc

class AudioSubscriptionEntryTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val endpointId = "test-endpoint"
    private val source1 = AudioSourceDesc(100L, "endpoint1", "source1")
    private val source2 = AudioSourceDesc(200L, "endpoint2", "source2")
    private val source3 = AudioSourceDesc(300L, "endpoint3", "source3")
    private val ownSource = AudioSourceDesc(400L, endpointId, "own-source")
    private val allSources = listOf(source1, source2, source3, ownSource)

    init {
        context("default wildcard subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            should("accept all SSRCs with wildcard include") {
                entry.isSsrcWanted(100L) shouldBe true
                entry.isSsrcWanted(200L) shouldBe true
                entry.isSsrcWanted(999L) shouldBe true
            }
        }

        context("wildcard exclude subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("source1", "source2"),
                exclude = listOf("*")
            )
            entry.updateSubscription(subscription, allSources)

            should("reject all SSRCs with wildcard exclude") {
                entry.isSsrcWanted(100L) shouldBe false
                entry.isSsrcWanted(200L) shouldBe false
                entry.isSsrcWanted(999L) shouldBe false
            }
        }

        context("specific include subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("source1", "source2"),
                exclude = emptyList()
            )
            entry.updateSubscription(subscription, allSources)

            should("accept only included SSRCs") {
                entry.isSsrcWanted(100L) shouldBe true // source1
                entry.isSsrcWanted(200L) shouldBe true // source2
                entry.isSsrcWanted(300L) shouldBe false // source3 not included
                entry.isSsrcWanted(999L) shouldBe false // unknown SSRC
            }
        }

        context("include with exclude subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("source1", "source2", "source3"),
                exclude = listOf("source2")
            )
            entry.updateSubscription(subscription, allSources)

            should("accept included SSRCs except excluded ones") {
                entry.isSsrcWanted(100L) shouldBe true // source1 included
                entry.isSsrcWanted(200L) shouldBe false // source2 excluded
                entry.isSsrcWanted(300L) shouldBe true // source3 included
                entry.isSsrcWanted(999L) shouldBe false // unknown SSRC
            }
        }

        context("own source filtering") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("source1", "own-source"),
                exclude = emptyList()
            )
            entry.updateSubscription(subscription, allSources)

            should("not include own sources in wanted SSRCs") {
                entry.isSsrcWanted(100L) shouldBe true // source1 included
                entry.isSsrcWanted(400L) shouldBe false // own-source filtered out
            }
        }

        context("onConferenceSourceAdded with specific subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("source1", "source2"),
                exclude = emptyList()
            )
            entry.updateSubscription(subscription, allSources)

            should("add matching new sources") {
                val newSource = AudioSourceDesc(500L, "endpoint4", "source4")
                val newMatchingSource = AudioSourceDesc(600L, "endpoint5", "source2")
                entry.onConferenceSourceAdded(setOf(newSource, newMatchingSource))
                entry.isSsrcWanted(500L) shouldBe false // source4 not in subscription
                entry.isSsrcWanted(600L) shouldBe true // source2 matches subscription
            }
        }

        context("onConferenceSourceAdded with wildcard subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("*"),
                exclude = emptyList()
            )
            entry.updateSubscription(subscription, allSources)
            should("not modify wanted SSRCs for wildcard subscription") {
                val newSource = AudioSourceDesc(500L, "endpoint4", "source4")
                entry.onConferenceSourceAdded(setOf(newSource))
                // Should still work with wildcard logic
                entry.isSsrcWanted(500L) shouldBe true
                entry.isSsrcWanted(999L) shouldBe true
            }
        }

        context("onConferenceSourceRemoved with specific subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("source1", "source2"),
                exclude = emptyList()
            )
            entry.updateSubscription(subscription, allSources)
            should("remove SSRCs of removed sources") {
                entry.isSsrcWanted(100L) shouldBe true
                entry.onConferenceSourceRemoved(setOf(source1))
                entry.isSsrcWanted(100L) shouldBe false
                entry.isSsrcWanted(200L) shouldBe true
            }
        }

        context("onConferenceSourceRemoved with wildcard subscription") {
            val entry = AudioSubscriptionEntry(endpointId)
            val subscription = ReceiverAudioSubscriptionMessage(
                include = listOf("*"),
                exclude = emptyList()
            )
            entry.updateSubscription(subscription, allSources)
            should("not modify wanted SSRCs for wildcard subscription") {
                entry.onConferenceSourceRemoved(setOf(source1))
                entry.isSsrcWanted(100L) shouldBe true
                entry.isSsrcWanted(200L) shouldBe true
            }
        }

        context("complex subscription updates") {
            val entry = AudioSubscriptionEntry(endpointId)
            should("handle subscription changes correctly") {
                val subscription1 = ReceiverAudioSubscriptionMessage(
                    include = listOf("source1"),
                    exclude = emptyList()
                )
                entry.updateSubscription(subscription1, allSources)
                entry.isSsrcWanted(100L) shouldBe true
                entry.isSsrcWanted(200L) shouldBe false
                // Change to wildcard
                val subscription2 = ReceiverAudioSubscriptionMessage(
                    include = listOf("*"),
                    exclude = emptyList()
                )
                entry.updateSubscription(subscription2, allSources)
                entry.isSsrcWanted(100L) shouldBe true
                entry.isSsrcWanted(200L) shouldBe true
                entry.isSsrcWanted(999L) shouldBe true
                // Back to specific
                val subscription3 = ReceiverAudioSubscriptionMessage(
                    include = listOf("source2", "source3"),
                    exclude = emptyList()
                )
                entry.updateSubscription(subscription3, allSources)
                entry.isSsrcWanted(100L) shouldBe false
                entry.isSsrcWanted(200L) shouldBe true
                entry.isSsrcWanted(300L) shouldBe true
            }
        }
    }
}

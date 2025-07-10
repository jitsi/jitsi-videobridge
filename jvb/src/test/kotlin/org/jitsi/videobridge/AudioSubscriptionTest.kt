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

class AudioSubscriptionTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val audioSubscription = AudioSubscription()

    init {
        context("Mode=None subscription") {
            should("return false for any SSRC when subscription is None") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.None, emptyList())
                audioSubscription.isSsrcWanted(1001L) shouldBe false
                audioSubscription.isSsrcWanted(1002L) shouldBe false
                audioSubscription.isSsrcWanted(9999L) shouldBe false
            }

            should("return false for any SSRC after adding conference sources") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.None, emptyList())
                val newSources = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                audioSubscription.onConferenceSourceAdded(newSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe false
                audioSubscription.isSsrcWanted(1002L) shouldBe false
            }

            should("handle source removal without affecting None behavior") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.None, emptyList())
                val sourcesToRemove = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                audioSubscription.onConferenceSourceRemoved(sourcesToRemove)
                audioSubscription.isSsrcWanted(1001L) shouldBe false
                audioSubscription.isSsrcWanted(1002L) shouldBe false
            }
        }

        context("Mode=All subscription") {
            should("return true for any SSRC when subscription is All") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, emptyList())
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
                audioSubscription.isSsrcWanted(9999L) shouldBe true
            }

            should("return true for any SSRC after adding conference sources") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, emptyList())
                val newSources = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                audioSubscription.onConferenceSourceAdded(newSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
            }

            should("handle source removal without affecting All behavior") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, emptyList())
                val sourcesToRemove = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                audioSubscription.onConferenceSourceRemoved(sourcesToRemove)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
            }
        }

        context("Mode=Custom subscription with include only") {
            should("return true only for sources in include list") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source3"),
                    exclude = emptyList()
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 included
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2 not included
                audioSubscription.isSsrcWanted(1003L) shouldBe true // source3 included
                audioSubscription.isSsrcWanted(9999L) shouldBe false // unknown SSRC
            }

            should("handle empty include list") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = emptyList(),
                    exclude = emptyList()
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe false
                audioSubscription.isSsrcWanted(1002L) shouldBe false
            }
        }

        context("Mode=Custom subscription with exclude only") {
            should("return true for sources not in exclude list") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source2", "source3"),
                    exclude = listOf("source2")
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 included, not excluded
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2 excluded
                audioSubscription.isSsrcWanted(1003L) shouldBe true // source3 included, not excluded
            }
        }

        context("Mode=Custom subscription with both include and exclude") {
            should("exclude takes precedence over include") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source2", "source3"),
                    exclude = listOf("source2")
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // included, not excluded
                audioSubscription.isSsrcWanted(1002L) shouldBe false // excluded overrides include
                audioSubscription.isSsrcWanted(1003L) shouldBe true // included, not excluded
            }

            should("handle complex include/exclude combinations") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3"),
                    AudioSourceDesc(1004L, "endpoint4", "source4"),
                    AudioSourceDesc(1005L, "endpoint5", "source5")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source2", "source3", "source4"),
                    exclude = listOf("source2", "source4")
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1: included, not excluded
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2: excluded
                audioSubscription.isSsrcWanted(1003L) shouldBe true // source3: included, not excluded
                audioSubscription.isSsrcWanted(1004L) shouldBe false // source4: excluded
                audioSubscription.isSsrcWanted(1005L) shouldBe false // source5: not included
            }
        }

        context("onConferenceSourceAdded with Custom subscription") {
            should("add new sources that match include/exclude criteria") {
                val initialSources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source2", "source3"),
                    exclude = listOf("source3")
                )
                audioSubscription.updateSubscription(customSubscription, initialSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 included
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2 not yet added
                val newSources = setOf(
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3"),
                    AudioSourceDesc(1004L, "endpoint4", "source4")
                )
                audioSubscription.onConferenceSourceAdded(newSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 still included
                audioSubscription.isSsrcWanted(1002L) shouldBe true // source2 now included
                audioSubscription.isSsrcWanted(1003L) shouldBe false // source3 excluded
                audioSubscription.isSsrcWanted(1004L) shouldBe false // source4 not in include list
            }

            should("not add sources for None subscription") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.None, emptyList())
                val newSources = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                audioSubscription.onConferenceSourceAdded(newSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe false
            }

            should("not change behavior for All subscription") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, emptyList())
                val newSources = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                audioSubscription.onConferenceSourceAdded(newSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
            }
        }

        context("onConferenceSourceRemoved") {
            should("remove sources from wanted list for Custom subscription") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source2"),
                    exclude = emptyList()
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
                val sourcesToRemove = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                audioSubscription.onConferenceSourceRemoved(sourcesToRemove)
                audioSubscription.isSsrcWanted(1001L) shouldBe false // removed from wanted list
                audioSubscription.isSsrcWanted(1002L) shouldBe true // still wanted
            }

            should("handle removal of non-existent sources") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1"),
                    exclude = emptyList()
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                val sourcesToRemove = setOf(
                    AudioSourceDesc(9999L, "endpoint999", "source999")
                )
                audioSubscription.onConferenceSourceRemoved(sourcesToRemove)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // unaffected
            }
        }

        context("Endpoint with multiple sources") {
            should("handle subscription for endpoint with multiple audio sources") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint1", "source2"),
                    AudioSourceDesc(1003L, "endpoint1", "source3"),
                    AudioSourceDesc(1004L, "endpoint2", "source4")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source3", "source4"),
                    exclude = listOf("source3")
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 included
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2 not included
                audioSubscription.isSsrcWanted(1003L) shouldBe false // source3 excluded
                audioSubscription.isSsrcWanted(1004L) shouldBe true // source4 included
            }

            should("handle All subscription for endpoint with multiple sources") {
                val sources = listOf(
                    AudioSourceDesc(2001L, "endpoint1", "source1"),
                    AudioSourceDesc(2002L, "endpoint1", "source2"),
                    AudioSourceDesc(2003L, "endpoint1", "source3")
                )
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, sources)
                audioSubscription.isSsrcWanted(2001L) shouldBe true
                audioSubscription.isSsrcWanted(2002L) shouldBe true
                audioSubscription.isSsrcWanted(2003L) shouldBe true
            }

            should("handle None subscription for endpoint with multiple sources") {
                val sources = listOf(
                    AudioSourceDesc(3001L, "endpoint1", "source1"),
                    AudioSourceDesc(3002L, "endpoint1", "source2"),
                    AudioSourceDesc(3003L, "endpoint1", "source3")
                )
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.None, sources)
                audioSubscription.isSsrcWanted(3001L) shouldBe false
                audioSubscription.isSsrcWanted(3002L) shouldBe false
                audioSubscription.isSsrcWanted(3003L) shouldBe false
            }
        }

        context("Subscription updates") {
            should("update from None to All") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.None, emptyList())
                audioSubscription.isSsrcWanted(1001L) shouldBe false
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, emptyList())
                audioSubscription.isSsrcWanted(1001L) shouldBe true
            }

            should("update from All to Custom") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, emptyList())
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                val customSubscription = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1"),
                    exclude = emptyList()
                )
                audioSubscription.updateSubscription(customSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe false
            }

            should("update Custom subscription with different include/exclude lists") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val customSubscription1 = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source1", "source2"),
                    exclude = emptyList()
                )
                audioSubscription.updateSubscription(customSubscription1, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
                audioSubscription.isSsrcWanted(1003L) shouldBe false
                val customSubscription2 = ReceiverAudioSubscriptionMessage.Custom(
                    include = listOf("source2", "source3"),
                    exclude = listOf("source2")
                )
                audioSubscription.updateSubscription(customSubscription2, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe false // not in new include list
                audioSubscription.isSsrcWanted(1002L) shouldBe false // excluded in new subscription
                audioSubscription.isSsrcWanted(1003L) shouldBe true // included in new subscription
            }
        }
    }
}

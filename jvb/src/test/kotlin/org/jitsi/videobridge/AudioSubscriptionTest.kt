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

        context("Mode=Include subscription") {
            should("return true only for sources in include list") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1", "source3")
                )
                audioSubscription.updateSubscription(includeSubscription, sources)
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
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = emptyList()
                )
                audioSubscription.updateSubscription(includeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe false
                audioSubscription.isSsrcWanted(1002L) shouldBe false
            }
        }

        context("Mode=Exclude subscription") {
            should("return true for sources not in exclude list") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val excludeSubscription = ReceiverAudioSubscriptionMessage.Exclude(
                    list = listOf("source2")
                )
                audioSubscription.updateSubscription(excludeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 not excluded
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2 excluded
                audioSubscription.isSsrcWanted(1003L) shouldBe true // source3 not excluded
            }

            should("handle empty exclude list") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                val excludeSubscription = ReceiverAudioSubscriptionMessage.Exclude(
                    list = emptyList()
                )
                audioSubscription.updateSubscription(excludeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // nothing excluded
                audioSubscription.isSsrcWanted(1002L) shouldBe true // nothing excluded
            }
        }

        context("onConferenceSourceAdded with Include subscription") {
            should("add new sources that match include criteria") {
                val initialSources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1", "source2", "source3")
                )
                audioSubscription.updateSubscription(includeSubscription, initialSources)
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
                audioSubscription.isSsrcWanted(1003L) shouldBe true // source3 now included
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

        context("onConferenceSourceAdded with Exclude subscription") {
            should("add new sources that don't match exclude criteria") {
                val initialSources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                val excludeSubscription = ReceiverAudioSubscriptionMessage.Exclude(
                    list = listOf("source2")
                )
                audioSubscription.updateSubscription(excludeSubscription, initialSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 not excluded
                val newSources = setOf(
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                audioSubscription.onConferenceSourceAdded(newSources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 still not excluded
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2 excluded
                audioSubscription.isSsrcWanted(1003L) shouldBe true // source3 not excluded
            }
        }

        context("onConferenceSourceRemoved") {
            should("remove sources from wanted list for Include subscription") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1", "source2")
                )
                audioSubscription.updateSubscription(includeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
                val sourcesToRemove = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                audioSubscription.onConferenceSourceRemoved(sourcesToRemove)
                audioSubscription.isSsrcWanted(1001L) shouldBe false // removed from wanted list
                audioSubscription.isSsrcWanted(1002L) shouldBe true // still wanted
            }

            should("remove sources from wanted list for Exclude subscription") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                val excludeSubscription = ReceiverAudioSubscriptionMessage.Exclude(
                    list = listOf("source3")
                )
                audioSubscription.updateSubscription(excludeSubscription, sources)
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
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1")
                )
                audioSubscription.updateSubscription(includeSubscription, sources)
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
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1", "source3", "source4"),
                )
                audioSubscription.updateSubscription(includeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // source1 included
                audioSubscription.isSsrcWanted(1002L) shouldBe false // source2 not included
                audioSubscription.isSsrcWanted(1003L) shouldBe true // source3 included
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

            should("update from All to Include") {
                audioSubscription.updateSubscription(ReceiverAudioSubscriptionMessage.All, emptyList())
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2")
                )
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1")
                )
                audioSubscription.updateSubscription(includeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe false
            }

            should("update from Include to Exclude") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1", "source2")
                )
                audioSubscription.updateSubscription(includeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
                audioSubscription.isSsrcWanted(1003L) shouldBe false
                val excludeSubscription = ReceiverAudioSubscriptionMessage.Exclude(
                    list = listOf("source2")
                )
                audioSubscription.updateSubscription(excludeSubscription, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true // not excluded
                audioSubscription.isSsrcWanted(1002L) shouldBe false // excluded
                audioSubscription.isSsrcWanted(1003L) shouldBe true // not excluded
            }

            should("update Include subscription with different list") {
                val sources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint2", "source2"),
                    AudioSourceDesc(1003L, "endpoint3", "source3")
                )
                val includeSubscription1 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1", "source2")
                )
                audioSubscription.updateSubscription(includeSubscription1, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe true
                audioSubscription.isSsrcWanted(1002L) shouldBe true
                audioSubscription.isSsrcWanted(1003L) shouldBe false
                val includeSubscription2 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source2", "source3")
                )
                audioSubscription.updateSubscription(includeSubscription2, sources)
                audioSubscription.isSsrcWanted(1001L) shouldBe false // not in new include list
                audioSubscription.isSsrcWanted(1002L) shouldBe true // in new include list
                audioSubscription.isSsrcWanted(1003L) shouldBe true // in new include list
            }
        }
    }
}

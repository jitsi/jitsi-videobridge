/*
 * Copyright @ 2025 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

class AudioSubscriptionManagerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    val audioSourceDescs: List<AudioSourceDesc> = listOf(
        AudioSourceDesc(1001L, "endpoint1", "source1"),
        AudioSourceDesc(1002L, "endpoint2", "source2"),
        AudioSourceDesc(1003L, "endpoint3", "source3")
    )

    // The manager owns the authoritative set of known sources; register the standard ones up front (as the conference
    // would via colibri) so subscriptions can resolve their names. Synthetic-source tests add more via onSourcesAdded.
    private val manager = AudioSubscriptionManager().apply { onSourcesAdded(audioSourceDescs.toSet()) }

    init {
        context("Basic subscription management") {
            should("handle single endpoint subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                // Endpoint should want included sources
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // source1
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true // source2
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false // source3 not included
            }

            should("handle multiple endpoint subscriptions") {
                val subscription1 = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1"))
                val subscription2 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source2", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.setEndpointAudioSubscription("endpoint2", subscription2)

                // endpoint1 subscriptions
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // source1
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false // source2
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false // source3

                // endpoint2 subscriptions
                manager.isEndpointAudioWanted("endpoint2", 1001L) shouldBe false // source1
                manager.isEndpointAudioWanted("endpoint2", 1002L) shouldBe true // source2
                manager.isEndpointAudioWanted("endpoint2", 1003L) shouldBe true // source3
            }

            should("handle All subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = true)
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true
                // An SSRC that corresponds to no known source is not routed, even under "all".
                manager.isEndpointAudioWanted("endpoint1", 9999L) shouldBe false
            }

            should("handle None subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false)
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false
            }

            should("handle Exclude subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // source1 not excluded
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false // source2 excluded
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true // source3 not excluded
            }
        }

        context("Subscribed local audio sources tracking") {
            should("track explicitly subscribed sources with Include subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source3"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe false
            }

            should("not track sources for All subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = true)
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("not track sources for None subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false)
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("not track sources for Exclude subscription") {
                val subscription = ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("track sources from multiple endpoints with Include subscriptions") {
                val subscription1 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                val subscription2 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source2", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.setEndpointAudioSubscription("endpoint2", subscription2)

                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
            }

            should("handle overlapping subscriptions correctly") {
                val subscription1 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                val subscription2 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.setEndpointAudioSubscription("endpoint2", subscription2)

                // source1 should be tracked (subscribed by both endpoints)
                // source2 should be tracked (subscribed by endpoint1)
                // source3 should be tracked (subscribed by endpoint2)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
            }

            should("update tracked sources when subscription changes") {
                val subscription1 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe false

                val subscription2 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source2", "source3"))
                manager.setEndpointAudioSubscription("endpoint1", subscription2)
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
            }
        }

        context("Endpoint removal") {
            should("remove endpoint from subscribed sources tracking") {
                val subscription1 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                val subscription2 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.setEndpointAudioSubscription("endpoint2", subscription2)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true

                val audioSources: Set<AudioSourceDesc> = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint1", "source2")
                )
                manager.removeEndpoint("endpoint1")
                manager.removeSources(audioSources)
                // Only source3 should remain (from endpoint2)
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe true
            }

            should("handle removal of non-existent endpoint") {
                manager.removeEndpoint("non-existent")
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("clean up empty source entries") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.removeEndpoint("endpoint1")
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("remove audio sources from subscribed sources when endpoint is removed") {
                val subscription1 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2", "source3"))
                val subscription2 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source2", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.setEndpointAudioSubscription("endpoint2", subscription2)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true

                val audioSources: Set<AudioSourceDesc> = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1"),
                    AudioSourceDesc(1002L, "endpoint1", "source2")
                )
                manager.removeEndpoint("endpoint1")
                manager.removeSources(audioSources)

                // Only source3 should remain (from endpoint2)
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe true
            }

            should("notify remaining subscriptions about removed sources") {
                val subscription1 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                val subscription2 =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.setEndpointAudioSubscription("endpoint2", subscription2)

                // Both endpoints should initially want source1
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint2", 1001L) shouldBe true

                val audioSources: Set<AudioSourceDesc> = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                manager.removeEndpoint("endpoint1")
                manager.removeSources(audioSources)

                // endpoint2 should no longer want source1 since it was removed from the conference
                manager.isEndpointAudioWanted("endpoint2", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint2", 1003L) shouldBe true // source3 should still be wanted
            }
        }

        context("Source lifecycle management") {
            should("handle source addition") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source4", "source5"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                // Initially, these sources don't exist, so they're not wanted
                manager.isEndpointAudioWanted("endpoint1", 1004L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1005L) shouldBe false

                val newSources = setOf(
                    AudioSourceDesc(1004L, "endpoint4", "source4"),
                    AudioSourceDesc(1005L, "endpoint5", "source5"),
                    AudioSourceDesc(1006L, "endpoint6", "source6")
                )
                manager.onSourcesAdded(newSources)

                // Now the included sources should be wanted
                manager.isEndpointAudioWanted("endpoint1", 1004L) shouldBe true // source4 included
                manager.isEndpointAudioWanted("endpoint1", 1005L) shouldBe true // source5 included
                manager.isEndpointAudioWanted("endpoint1", 1006L) shouldBe false // source6 not included
            }

            should("resolve a subscription naming a source that was signaled before it") {
                // Regression: a ReceiverAudioSubscription can arrive after a source is signaled via colibri but
                // referencing it by name. The subscription must resolve against the conference's known sources, not
                // a snapshot captured by the caller, otherwise the source is silently never forwarded.
                val lateSource = AudioSourceDesc(1007L, "endpoint7", "source7")
                manager.onSourcesAdded(setOf(lateSource))

                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source7"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isEndpointAudioWanted("endpoint1", 1007L) shouldBe true
            }

            should("handle source removal") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true

                val removedSources = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                manager.removeSources(removedSources)

                // Removed source should no longer be wanted
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true // still wanted
            }

            should("clean up subscribed sources when sources are removed") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true

                val removedSources = setOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                manager.removeSources(removedSources)

                // source1 should be removed from subscribed sources
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe true
            }
        }

        context("Complex scenarios") {
            should("handle multiple endpoints with mixed subscription types") {
                val allSub = ReceiverAudioSubscriptionMessage(all = true)
                val noneSub = ReceiverAudioSubscriptionMessage(all = false)
                val includeSub = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "source3"))
                val excludeSub = ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("source2"))

                manager.setEndpointAudioSubscription("endpoint-all", allSub)
                manager.setEndpointAudioSubscription("endpoint-none", noneSub)
                manager.setEndpointAudioSubscription("endpoint-include", includeSub)
                manager.setEndpointAudioSubscription("endpoint-exclude", excludeSub)

                // endpoint-all should want everything
                manager.isEndpointAudioWanted("endpoint-all", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint-all", 1002L) shouldBe true
                manager.isEndpointAudioWanted("endpoint-all", 1003L) shouldBe true

                // endpoint-none should want nothing
                manager.isEndpointAudioWanted("endpoint-none", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint-none", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint-none", 1003L) shouldBe false

                // endpoint-include should want only included sources
                manager.isEndpointAudioWanted("endpoint-include", 1001L) shouldBe true // source1
                manager.isEndpointAudioWanted("endpoint-include", 1002L) shouldBe false // source2
                manager.isEndpointAudioWanted("endpoint-include", 1003L) shouldBe true // source3

                // endpoint-exclude should want all except excluded sources
                manager.isEndpointAudioWanted("endpoint-exclude", 1001L) shouldBe true // source1
                manager.isEndpointAudioWanted("endpoint-exclude", 1002L) shouldBe false // source2 (excluded)
                manager.isEndpointAudioWanted("endpoint-exclude", 1003L) shouldBe true // source3

                // Only include subscription should contribute to subscribed sources
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe false
            }

            should("handle subscription changes across different types") {
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true)
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1"))
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = false)
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("source2"))
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("maintain independent endpoint subscriptions") {
                val subscription1 = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1"))
                val subscription2 = ReceiverAudioSubscriptionMessage(all = false, include = listOf("source2"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1)
                manager.setEndpointAudioSubscription("endpoint2", subscription2)

                // Verify independent subscriptions
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint2", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint2", 1002L) shouldBe true

                // Update one subscription shouldn't affect the other
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true)
                )
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true // now wants all
                manager.isEndpointAudioWanted("endpoint2", 1001L) shouldBe false // unchanged
                manager.isEndpointAudioWanted("endpoint2", 1002L) shouldBe true // unchanged

                manager.removeEndpoint("endpoint1")
                manager.isEndpointAudioWanted("endpoint2", 1002L) shouldBe true // unchanged
            }
        }

        context("Edge cases") {
            should("handle empty include list") {
                val subscription = ReceiverAudioSubscriptionMessage(all = false, include = emptyList())
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("handle empty exclude list") {
                val subscription = ReceiverAudioSubscriptionMessage(all = true, exclude = emptyList())
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                // Nothing excluded means everything is wanted
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("handle subscription to non-existent sources") {
                val subscription =
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("non-existent-source"))
                manager.setEndpointAudioSubscription("endpoint1", subscription)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false

                // Should still track the explicitly subscribed source name
                manager.isExplicitlySubscribed("non-existent-source") shouldBe true
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }
        }

        context("Synthetic sources") {
            // ssrc 2001 / "syntheticSource" is synthetic; the regular audioSourceDescs (1001-1003) are not.
            val syntheticSsrc = 2001L
            val syntheticDesc = AudioSourceDesc(syntheticSsrc, "endpointS", "syntheticSource", synthetic = true)

            should("not be wanted under an All subscription") {
                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true)
                )

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // non-synthetic still wanted
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe false
            }

            should("not be wanted by an endpoint with no subscription (default)") {
                manager.onSourcesAdded(setOf(syntheticDesc))

                // No subscription set for this endpoint: defaults to wanting everything except synthetic.
                manager.isEndpointAudioWanted("no-subscription", 1001L) shouldBe true
                manager.isEndpointAudioWanted("no-subscription", syntheticSsrc) shouldBe false
            }

            should("not be wanted under an Exclude subscription that doesn't name it") {
                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("source2"))
                )

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // non-synthetic, not excluded
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe false
            }

            should("be wanted only when explicitly included by name") {
                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("syntheticSource"))
                )

                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe true
            }

            should("be wanted under an All subscription that also includes it") {
                // The motivating case: "all regular audio plus this synthetic source", which the old
                // mode-based message couldn't express.
                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true, include = listOf("syntheticSource"))
                )

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // regular audio via `all`
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe true // synthetic via `include`
            }

            should("not be wanted by an Include subscription that names a different source") {
                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1"))
                )

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // explicitly included
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe false
            }

            should("be resolvable by name only while known, and only when synthetic") {
                manager.findSyntheticSource("syntheticSource") shouldBe null // not added yet
                manager.findSyntheticSource("source1") shouldBe null // known but not synthetic

                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.findSyntheticSource("syntheticSource") shouldBe syntheticDesc
                manager.findSyntheticSource("source1") shouldBe null // still not synthetic

                manager.removeSources(setOf(syntheticDesc))
                manager.findSyntheticSource("syntheticSource") shouldBe null // gone after removal
            }

            should("not be routed once removed, even to an All subscriber (no leak as ordinary audio)") {
                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true)
                )
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe false

                manager.removeSources(setOf(syntheticDesc))
                // Once removed the SSRC corresponds to no known source, so it must not be routed -- in particular it
                // must not revert to being delivered as ordinary audio under an "all" subscription. This guards
                // against late synthetic packets (translator media arriving just after the source was unsubscribed)
                // leaking to non-subscribers.
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe false
            }

            should("not be routed before it is known, even to an All subscriber (early-packet leak)") {
                // The mirror of the late case: media for a synthetic SSRC that arrives before colibri has signaled
                // the source must not be forwarded as ordinary audio.
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true)
                )
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe false // not yet known

                manager.onSourcesAdded(setOf(syntheticDesc))
                // Now known and synthetic: still gated to explicit (Include) subscribers only, not "all".
                manager.isEndpointAudioWanted("endpoint1", syntheticSsrc) shouldBe false
            }
        }

        context("Debug state") {
            val syntheticSsrc = 2001L
            val syntheticDesc = AudioSourceDesc(syntheticSsrc, "endpointS", "syntheticSource", synthetic = true)

            should("report known sources, subscriptions, and explicit subscribers") {
                manager.onSourcesAdded(setOf(syntheticDesc))
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage(all = true, exclude = listOf("source2"))
                )
                manager.setEndpointAudioSubscription(
                    "endpoint2",
                    ReceiverAudioSubscriptionMessage(all = false, include = listOf("source1", "syntheticSource"))
                )

                val state = manager.debugState()

                // known_sources lists every known source with its synthetic flag (regulars + the synthetic one).
                val knownSsrcs = state.get("known_sources").map { it.get("ssrc").asLong() }.toSet()
                knownSsrcs shouldBe setOf(1001L, 1002L, 1003L, syntheticSsrc)
                val syntheticEntry = state.get("known_sources").single { it.get("ssrc").asLong() == syntheticSsrc }
                syntheticEntry.get("synthetic").asBoolean() shouldBe true

                // subscribed_local_sources reflects only Include subscriptions.
                val subscribed = state.get("subscribed_local_sources")
                subscribed.has("source1") shouldBe true
                subscribed.has("syntheticSource") shouldBe true
                subscribed.has("source2") shouldBe false // only excluded, not an explicit subscription

                // Per-endpoint subscription state, including resolved SSRCs.
                val ep1 = state.get("subscriptions").get("endpoint1")
                ep1.get("all").asBoolean() shouldBe true
                ep1.get("excluded_ssrcs").map { it.asLong() } shouldBe listOf(1002L)
                val ep2 = state.get("subscriptions").get("endpoint2")
                ep2.get("all").asBoolean() shouldBe false
                ep2.get("included_ssrcs").map { it.asLong() }.toSet() shouldBe setOf(1001L, syntheticSsrc)
            }
        }
    }
}

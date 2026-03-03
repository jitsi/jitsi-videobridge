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
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.jitsi.videobridge.message.ReceiverAudioSubscriptionMessage
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.relay.RelayedEndpoint

class AudioSubscriptionManagerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    val audioSourceDescs: List<AudioSourceDesc> = listOf(
        AudioSourceDesc(1001L, "endpoint1", "source1"),
        AudioSourceDesc(1002L, "endpoint2", "source2"),
        AudioSourceDesc(1003L, "endpoint3", "source3")
    )

    private val mockRelay = mockk<Relay>()
    private val mockRelayedEndpoint = mockk<RelayedEndpoint>()
    private val mockLocalEndpoint = mockk<Endpoint>()

    private fun createManagerWithMockSourceOwner(): AudioSubscriptionManager {
        every { mockRelayedEndpoint.relay } returns mockRelay
        every { mockRelay.sendMessage(any()) } just Runs

        return AudioSubscriptionManager { sourceName ->
            when (sourceName) {
                "remote1", "remote2", "remote3" -> mockRelayedEndpoint
                "source1", "source2", "source3" -> mockLocalEndpoint
                else -> null
            }
        }
    }

    private val manager = AudioSubscriptionManager()

    init {
        context("Basic subscription management") {
            should("handle single endpoint subscription") {
                val subscription = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                // Endpoint should want included sources
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // source1
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true // source2
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false // source3 not included
            }

            should("handle multiple endpoint subscriptions") {
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1"))
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source2", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint2", subscription2, audioSourceDescs)

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
                val subscription = ReceiverAudioSubscriptionMessage.All
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 9999L) shouldBe true // unknown SSRC
            }

            should("handle None subscription") {
                val subscription = ReceiverAudioSubscriptionMessage.None
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false
            }

            should("handle Exclude subscription") {
                val subscription = ReceiverAudioSubscriptionMessage.Exclude(listOf("source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true // source1 not excluded
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false // source2 excluded
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true // source3 not excluded
            }
        }

        context("Subscribed local audio sources tracking") {
            should("track explicitly subscribed sources with Include subscription") {
                val subscription = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source3"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe false
            }

            should("not track sources for All subscription") {
                val subscription = ReceiverAudioSubscriptionMessage.All
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("not track sources for None subscription") {
                val subscription = ReceiverAudioSubscriptionMessage.None
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("not track sources for Exclude subscription") {
                val subscription = ReceiverAudioSubscriptionMessage.Exclude(listOf("source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("track sources from multiple endpoints with Include subscriptions") {
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source2", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint2", subscription2, audioSourceDescs)

                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
            }

            should("handle overlapping subscriptions correctly") {
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint2", subscription2, audioSourceDescs)

                // source1 should be tracked (subscribed by both endpoints)
                // source2 should be tracked (subscribed by endpoint1)
                // source3 should be tracked (subscribed by endpoint2)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
            }

            should("update tracked sources when subscription changes") {
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe false

                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source2", "source3"))
                manager.setEndpointAudioSubscription("endpoint1", subscription2, audioSourceDescs)
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe true
                manager.isExplicitlySubscribed("source3") shouldBe true
            }
        }

        context("Endpoint removal") {
            should("remove endpoint from subscribed sources tracking") {
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint2", subscription2, audioSourceDescs)
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
                val subscription = ReceiverAudioSubscriptionMessage.Include(listOf("source1"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.removeEndpoint("endpoint1")
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("remove audio sources from subscribed sources when endpoint is removed") {
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2", "source3"))
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source2", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint2", subscription2, audioSourceDescs)
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
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source3"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint2", subscription2, audioSourceDescs)

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
                val subscription = ReceiverAudioSubscriptionMessage.Include(listOf("source4", "source5"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

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

            should("handle source removal") {
                val subscription = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)
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
                val subscription = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source2"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)
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
                val allSub = ReceiverAudioSubscriptionMessage.All
                val noneSub = ReceiverAudioSubscriptionMessage.None
                val includeSub = ReceiverAudioSubscriptionMessage.Include(listOf("source1", "source3"))
                val excludeSub = ReceiverAudioSubscriptionMessage.Exclude(listOf("source2"))

                manager.setEndpointAudioSubscription("endpoint-all", allSub, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint-none", noneSub, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint-include", includeSub, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint-exclude", excludeSub, audioSourceDescs)

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
                    ReceiverAudioSubscriptionMessage.All,
                    audioSourceDescs
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage.Include(listOf("source1")),
                    audioSourceDescs
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isExplicitlySubscribed("source1") shouldBe true
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage.None,
                    audioSourceDescs
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false

                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage.Exclude(listOf("source2")),
                    audioSourceDescs
                )
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("maintain independent endpoint subscriptions") {
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(listOf("source1"))
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(listOf("source2"))

                manager.setEndpointAudioSubscription("endpoint1", subscription1, audioSourceDescs)
                manager.setEndpointAudioSubscription("endpoint2", subscription2, audioSourceDescs)

                // Verify independent subscriptions
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint2", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint2", 1002L) shouldBe true

                // Update one subscription shouldn't affect the other
                manager.setEndpointAudioSubscription(
                    "endpoint1",
                    ReceiverAudioSubscriptionMessage.All,
                    audioSourceDescs
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
                val subscription = ReceiverAudioSubscriptionMessage.Include(emptyList())
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe false
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe false
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("handle empty exclude list") {
                val subscription = ReceiverAudioSubscriptionMessage.Exclude(emptyList())
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

                // Nothing excluded means everything is wanted
                manager.isEndpointAudioWanted("endpoint1", 1001L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1002L) shouldBe true
                manager.isEndpointAudioWanted("endpoint1", 1003L) shouldBe true
                manager.isExplicitlySubscribed("source1") shouldBe false
                manager.isExplicitlySubscribed("source2") shouldBe false
                manager.isExplicitlySubscribed("source3") shouldBe false
            }

            should("handle subscription to non-existent sources") {
                val subscription = ReceiverAudioSubscriptionMessage.Include(listOf("non-existent-source"))
                manager.setEndpointAudioSubscription("endpoint1", subscription, audioSourceDescs)

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
        context("Remote audio source subscriptions") {
            should("add remote sources to subscribedRemoteAudioSources when endpoint subscribes to them") {
                val managerWithMocks = createManagerWithMockSourceOwner()
                val localSources = listOf(
                    AudioSourceDesc(1001L, "endpoint1", "source1")
                )
                val includeSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("source1", "remote1", "remote2")
                )

                managerWithMocks.setEndpointAudioSubscription("endpoint1", includeSubscription, localSources)

                // Local source should not be in remote sources
                managerWithMocks.isExplicitlySubscribed("source1") shouldBe true
                managerWithMocks.isRemoteSourceExplicitlySubscribed("source1") shouldBe false

                // Remote sources should be tracked
                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote1") shouldBe true
                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote2") shouldBe true
                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint1")
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe setOf("endpoint1")
            }

            should("handle multiple endpoints subscribing to same remote source") {
                val managerWithMocks = createManagerWithMockSourceOwner()
                val includeSubscription1 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1", "remote2")
                )
                val includeSubscription2 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1", "remote3")
                )

                managerWithMocks.setEndpointAudioSubscription("endpoint1", includeSubscription1, emptyList())
                managerWithMocks.setEndpointAudioSubscription("endpoint2", includeSubscription2, emptyList())

                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint1", "endpoint2")
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe setOf("endpoint1")
                managerWithMocks.getRemoteSourceSubscribers("remote3") shouldBe setOf("endpoint2")
            }

            should("remove old remote sources when endpoint updates subscription") {
                val managerWithMocks = createManagerWithMockSourceOwner()
                val initialSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1", "remote2")
                )
                val updatedSubscription = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote2", "remote3")
                )

                managerWithMocks.setEndpointAudioSubscription("endpoint1", initialSubscription, emptyList())
                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint1")
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe setOf("endpoint1")

                managerWithMocks.setEndpointAudioSubscription("endpoint1", updatedSubscription, emptyList())

                // remote1 should be removed, remote2 should remain, remote3 should be added
                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote1") shouldBe false
                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe emptySet()
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe setOf("endpoint1")
                managerWithMocks.getRemoteSourceSubscribers("remote3") shouldBe setOf("endpoint1")
            }

            should("remove remote source when last subscriber is removed") {
                val managerWithMocks = createManagerWithMockSourceOwner()
                val includeSubscription1 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1")
                )
                val includeSubscription2 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1")
                )
                val noneSubscription = ReceiverAudioSubscriptionMessage.None

                // Two endpoints subscribe to same remote source
                managerWithMocks.setEndpointAudioSubscription("endpoint1", includeSubscription1, emptyList())
                managerWithMocks.setEndpointAudioSubscription("endpoint2", includeSubscription2, emptyList())
                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint1", "endpoint2")

                // First endpoint unsubscribes (switches to None)
                managerWithMocks.setEndpointAudioSubscription("endpoint1", noneSubscription, emptyList())
                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint2")
                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote1") shouldBe true

                // Last endpoint unsubscribes - source should be completely removed
                managerWithMocks.setEndpointAudioSubscription("endpoint2", noneSubscription, emptyList())
                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote1") shouldBe false
                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe emptySet()
            }

            should("handle endpoint removal from conference") {
                val managerWithMocks = createManagerWithMockSourceOwner()
                val includeSubscription1 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1", "remote2")
                )
                val includeSubscription2 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1")
                )

                managerWithMocks.setEndpointAudioSubscription("endpoint1", includeSubscription1, emptyList())
                managerWithMocks.setEndpointAudioSubscription("endpoint2", includeSubscription2, emptyList())

                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint1", "endpoint2")
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe setOf("endpoint1")

                // Remove endpoint1 from conference
                managerWithMocks.removeEndpoint("endpoint1")

                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint2")
                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote1") shouldBe true
                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote2") shouldBe false
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe emptySet()
            }

            should("correctly handle overlapping subscriptions from multiple endpoints") {
                val managerWithMocks = createManagerWithMockSourceOwner()
                val subscription1 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote1", "remote2")
                )
                val subscription2 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote2", "remote3")
                )
                val updatedSubscription1 = ReceiverAudioSubscriptionMessage.Include(
                    list = listOf("remote3")
                )

                // Initial subscriptions
                managerWithMocks.setEndpointAudioSubscription("endpoint1", subscription1, emptyList())
                managerWithMocks.setEndpointAudioSubscription("endpoint2", subscription2, emptyList())

                managerWithMocks.getRemoteSourceSubscribers("remote1") shouldBe setOf("endpoint1")
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe setOf("endpoint1", "endpoint2")
                managerWithMocks.getRemoteSourceSubscribers("remote3") shouldBe setOf("endpoint2")

                // Update endpoint1 subscription
                managerWithMocks.setEndpointAudioSubscription("endpoint1", updatedSubscription1, emptyList())

                managerWithMocks.isRemoteSourceExplicitlySubscribed("remote1") shouldBe false // No subscribers left
                managerWithMocks.getRemoteSourceSubscribers("remote2") shouldBe setOf("endpoint2")
                managerWithMocks.getRemoteSourceSubscribers("remote3") shouldBe setOf("endpoint1", "endpoint2")
            }
        }
    }
}

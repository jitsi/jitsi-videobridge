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

package org.jitsi.videobridge.health

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.ice4j.TransportAddress
import org.ice4j.ice.harvest.HarvestConfig
import org.ice4j.ice.harvest.MappingCandidateHarvester
import org.ice4j.ice.harvest.MappingCandidateHarvesters
import org.ice4j.ice.harvest.SinglePortUdpHarvester
import org.jitsi.videobridge.ice.Harvesters
import java.net.InetAddress

/**
 * Tests for [JvbHealthChecker.hasValidAddress], specifically verifying that deployments
 * with site-local addresses paired with NAT/mapping harvesters, static mappings, or explicit
 * harvesting restrictions are correctly treated as valid (fix for jitsi/jitsi-videobridge#2435).
 */
class JvbHealthCheckerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        afterSpec { unmockkAll() }

        context("hasValidAddress") {
            context("with a publicly routable single-port harvester address") {
                should("return true") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(PUBLIC_ADDRESS),
                        mappingHarvesters = emptyArray()
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with only a site-local single-port harvester and no mapping harvesters or restrictions") {
                should("return false") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = emptyArray()
                    )
                    checker.hasValidAddress() shouldBe false
                }
            }

            context("with only a loopback single-port harvester and no mapping harvesters") {
                should("return false") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(LOOPBACK_ADDRESS),
                        mappingHarvesters = emptyArray()
                    )
                    checker.hasValidAddress() shouldBe false
                }
            }

            context("with only a link-local single-port harvester and no mapping harvesters") {
                should("return false") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(LINK_LOCAL_ADDRESS),
                        mappingHarvesters = emptyArray()
                    )
                    checker.hasValidAddress() shouldBe false
                }
            }

            context("with no single-port harvesters and no mapping harvesters") {
                should("return false") {
                    val checker = createChecker(
                        singlePortAddresses = emptyList(),
                        mappingHarvesters = emptyArray()
                    )
                    checker.hasValidAddress() shouldBe false
                }
            }

            context("with a site-local single-port harvester and a mapping harvester with a public mask") {
                should("return true — NAT maps site-local to public") {
                    val mappingHarvester = mockk<MappingCandidateHarvester>()
                    every { mappingHarvester.mask } returns TransportAddress(
                        PUBLIC_ADDRESS,
                        10000,
                        org.ice4j.Transport.UDP
                    )

                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = arrayOf(mappingHarvester)
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with a site-local single-port harvester and a mapping harvester with a site-local mask") {
                should("return true — operator configured NAT mapping, trust the deployment") {
                    val mappingHarvester = mockk<MappingCandidateHarvester>()
                    every { mappingHarvester.mask } returns TransportAddress(
                        SITE_LOCAL_ADDRESS_2,
                        10000,
                        org.ice4j.Transport.UDP
                    )

                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = arrayOf(mappingHarvester)
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with a site-local single-port harvester and a mapping harvester with null mask") {
                should("return true — harvester exists even if mask not yet resolved") {
                    val mappingHarvester = mockk<MappingCandidateHarvester>()
                    every { mappingHarvester.mask } returns null

                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = arrayOf(mappingHarvester)
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with multiple single-port harvesters (all site-local) and a public mapping harvester") {
                should("return true — typical multi-interface behind NAT scenario (issue #2435)") {
                    val mappingHarvester = mockk<MappingCandidateHarvester>()
                    every { mappingHarvester.mask } returns TransportAddress(
                        PUBLIC_ADDRESS,
                        10000,
                        org.ice4j.Transport.UDP
                    )

                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS, SITE_LOCAL_ADDRESS_2),
                        mappingHarvesters = arrayOf(mappingHarvester)
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with multiple single-port harvesters where one is public") {
                should("return true via the fast path") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS, PUBLIC_ADDRESS),
                        mappingHarvesters = emptyArray()
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with a site-local single-port harvester and static mappings in HarvestConfig") {
                should("return true — static mapping configured (even when local == public)") {
                    val staticMapping = mockk<HarvestConfig.StaticMapping>()
                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = emptyArray(),
                        staticMappings = setOf(staticMapping)
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with a site-local single-port harvester matching ALLOWED_ADDRESSES") {
                should("return true — operator explicitly restricted to this address") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = emptyArray(),
                        allowedAddresses = listOf(SITE_LOCAL_ADDRESS)
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with a site-local single-port harvester and BLOCKED_INTERFACES configured") {
                should("return true — operator explicitly filtered interfaces") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = emptyArray(),
                        blockedInterfaces = listOf("epair0a")
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with a site-local single-port harvester and ALLOWED_INTERFACES configured") {
                should("return true — operator explicitly specified allowed interfaces") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(SITE_LOCAL_ADDRESS),
                        mappingHarvesters = emptyArray(),
                        allowedInterfaces = listOf("epair0b")
                    )
                    checker.hasValidAddress() shouldBe true
                }
            }

            context("with loopback address and BLOCKED_INTERFACES configured") {
                should("return false — loopback is never accepted even with interface filters") {
                    val checker = createChecker(
                        singlePortAddresses = listOf(LOOPBACK_ADDRESS),
                        mappingHarvesters = emptyArray(),
                        blockedInterfaces = listOf("epair0a")
                    )
                    checker.hasValidAddress() shouldBe false
                }
            }
        }
    }

    /**
     * Creates a [JvbHealthChecker] with mocked [Harvesters], [MappingCandidateHarvesters],
     * and [HarvestConfig] to simulate specific network interface configurations.
     */
    private fun createChecker(
        singlePortAddresses: List<InetAddress>,
        mappingHarvesters: Array<MappingCandidateHarvester>,
        staticMappings: Set<HarvestConfig.StaticMapping> = emptySet(),
        allowedAddresses: List<InetAddress> = emptyList(),
        blockedInterfaces: List<String> = emptyList(),
        allowedInterfaces: List<String> = emptyList()
    ): JvbHealthChecker {
        val mockSinglePortHarvesters = singlePortAddresses.map { addr ->
            mockk<SinglePortUdpHarvester>().also {
                every { it.localAddress } returns TransportAddress(addr, 10000, org.ice4j.Transport.UDP)
            }
        }

        mockkObject(Harvesters.Companion)
        val mockHarvesters = mockk<Harvesters>()
        every { mockHarvesters.singlePortHarvesters } returns mockSinglePortHarvesters
        every { Harvesters.INSTANCE } returns mockHarvesters

        mockkStatic(MappingCandidateHarvesters::class)
        every { MappingCandidateHarvesters.getHarvesters() } returns mappingHarvesters

        mockkObject(HarvestConfig.Companion)
        val mockHarvestConfig = mockk<HarvestConfig>(relaxed = true)
        every { mockHarvestConfig.staticMappings } returns staticMappings
        every { mockHarvestConfig.allowedAddresses } returns allowedAddresses
        every { mockHarvestConfig.blockedInterfaces } returns blockedInterfaces
        every { mockHarvestConfig.allowedInterfaces } returns allowedInterfaces
        every { HarvestConfig.config } returns mockHarvestConfig

        return JvbHealthChecker()
    }

    companion object {
        /** A publicly routable address (e.g. a cloud provider's public IP). */
        private val PUBLIC_ADDRESS: InetAddress = InetAddress.getByName("203.0.113.1")

        /** A site-local (RFC 1918) address — typical for containers, jails, VMs behind NAT. */
        private val SITE_LOCAL_ADDRESS: InetAddress = InetAddress.getByName("10.0.0.1")

        /** A second site-local address — simulates a multi-interface host with two private addresses. */
        private val SITE_LOCAL_ADDRESS_2: InetAddress = InetAddress.getByName("192.168.1.1")

        /** A loopback address. */
        private val LOOPBACK_ADDRESS: InetAddress = InetAddress.getByName("127.0.0.1")

        /** A link-local address. */
        private val LINK_LOCAL_ADDRESS: InetAddress = InetAddress.getByName("169.254.1.1")
    }
}

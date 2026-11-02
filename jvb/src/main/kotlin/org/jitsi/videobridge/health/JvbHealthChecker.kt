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

import org.ice4j.ice.harvest.HarvestConfig
import org.ice4j.ice.harvest.MappingCandidateHarvesters
import org.jitsi.health.HealthCheckService
import org.jitsi.health.HealthChecker
import org.jitsi.health.Result
import org.jitsi.videobridge.health.config.HealthConfig.Companion.config
import org.jitsi.videobridge.ice.Harvesters
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import java.net.InetAddress

class JvbHealthChecker : HealthCheckService {
    private val healthChecker = HealthChecker(
        config.interval,
        config.timeout,
        config.maxCheckDuration,
        config.stickyFailures,
        healthCheckFunc = ::checkAndUpdateMetric
    )

    fun start() = healthChecker.start()
    fun stop() = healthChecker.stop()

    private fun checkAndUpdateMetric(): Result = check().also {
        healthyMetric.set(it.success)
    }

    private fun check(): Result {
        if (config.requireValidAddress && !hasValidAddress()) {
            return Result(success = false, message = "No valid IP addresses available for harvesting.")
        }
        if (config.requireStun && MappingCandidateHarvesters.stunDiscoveryFailed) {
            return Result(success = false, message = "Address discovery through STUN failed")
        }
        if (!Harvesters.INSTANCE.healthy) {
            return Result(success = false, message = "Failed to bind single-port")
        }

        // TODO: check if XmppConnection is configured and connected.

        return Result(success = true)
    }

    /**
     * Checks whether [this] address is directly publicly routable (i.e. not site-local,
     * link-local, or loopback).
     */
    private fun InetAddress.isPubliclyRoutable(): Boolean =
        !this.isSiteLocalAddress && !this.isLinkLocalAddress && !this.isLoopbackAddress

    /**
     * Determines whether the videobridge has at least one valid address available
     * for ICE candidate harvesting.
     *
     * An address is considered valid if any of the following holds:
     * 1. A [SinglePortUdpHarvester] is bound to a publicly routable address.
     * 2. A [MappingCandidateHarvester] (e.g. NAT or STUN harvester) has a publicly
     *    routable public (mask) address — this covers deployments where the
     *    [SinglePortUdpHarvester] binds to a site-local address that is mapped to a
     *    public address via NAT.
     * 3. At least one [SinglePortUdpHarvester] is bound AND at least one
     *    [MappingCandidateHarvester] exists — this covers deployments where the
     *    mapping harvester's mask may not yet have been resolved or where it maps
     *    between two site-local addresses (e.g. container-to-host NAT).
     * 4. At least one [SinglePortUdpHarvester] is bound AND static mappings are
     *    configured in [HarvestConfig] (e.g. NAT_HARVESTER_LOCAL_ADDRESS /
     *    NAT_HARVESTER_PUBLIC_ADDRESS, even when local == public, which ice4j prunes
     *    from [MappingCandidateHarvesters]).
     * 5. The operator has explicitly configured harvesting restrictions:
     *    - [HarvestConfig.allowedAddresses] is specified and matches a bound harvester.
     *    - [HarvestConfig.allowedInterfaces] or [HarvestConfig.blockedInterfaces] is
     *      specified and a non-loopback harvester is bound.
     */
    internal fun hasValidAddress(): Boolean {
        val harvesters = Harvesters.INSTANCE.singlePortHarvesters

        // Fast path: a single-port harvester bound to a publicly routable address.
        if (harvesters.any { it.localAddress.address.isPubliclyRoutable() }) {
            return true
        }

        val mappingHarvesters = MappingCandidateHarvesters.getHarvesters()

        // A mapping harvester provides a publicly routable public (mask) address —
        // this is the typical NAT/STUN case where the local bind address is site-local
        // but the mapped public address is globally reachable.
        if (mappingHarvesters.any { it.mask?.address?.isPubliclyRoutable() == true }) {
            return true
        }

        // If there are bound single-port harvesters AND at least one mapping harvester
        // is configured, accept the deployment as valid. In multi-interface or
        // container/jail setups, both the local and mapped addresses may be site-local
        // (e.g. container-private → host-private, with the host doing the final NAT).
        // The health check should not reject this — the operator explicitly configured
        // harvesting and NAT mapping, so trust that the path works.
        if (harvesters.isNotEmpty() && mappingHarvesters.isNotEmpty()) {
            return true
        }

        // If static mappings are configured in HarvestConfig, trust the operator's mapping
        // even if local == public (which ice4j prunes from MappingCandidateHarvesters).
        if (harvesters.isNotEmpty() && HarvestConfig.config.staticMappings.isNotEmpty()) {
            return true
        }

        // If the operator explicitly restricted harvesting to specific addresses
        // (ALLOWED_ADDRESSES) or interfaces (BLOCKED_INTERFACES / ALLOWED_INTERFACES),
        // and single-port harvesters are successfully bound to non-loopback addresses,
        // trust the operator's explicit harvesting restrictions.
        val harvestConfig = HarvestConfig.config
        val hasExplicitAddressRestriction = harvestConfig.allowedAddresses.isNotEmpty() &&
            harvesters.any { it.localAddress.address in harvestConfig.allowedAddresses }
        val hasExplicitInterfaceRestriction = (
            harvestConfig.allowedInterfaces.isNotEmpty() ||
                harvestConfig.blockedInterfaces.isNotEmpty()
            ) &&
            harvesters.any { !it.localAddress.address.isLoopbackAddress }

        if (hasExplicitAddressRestriction || hasExplicitInterfaceRestriction) {
            return true
        }

        return false
    }

    override val result: Result
        get() = healthChecker.result

    companion object {
        val healthyMetric = VideobridgeMetricsContainer.instance.registerBooleanMetric(
            "healthy",
            "Whether the Videobridge instance is healthy or not.",
            true
        )
    }
}

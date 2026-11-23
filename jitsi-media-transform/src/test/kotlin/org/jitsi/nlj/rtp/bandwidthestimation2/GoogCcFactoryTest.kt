/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.rtp.scream.ScreamNetworkController
import org.jitsi.nlj.util.kbps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger

/** Jitsi local: tests of which network controller GoogCcNetworkControllerFactory creates. */
class GoogCcFactoryTest : ShouldSpec() {
    private val logger = createLogger()

    private fun config() = NetworkControllerConfig(
        logger,
        DiagnosticContext(),
        constraints = TargetRateConstraints(startingRate = 300.kbps)
    )

    private fun create(screamMode: ScreamMode, rfc8888FeedbackNegotiated: Boolean) = GoogCcNetworkControllerFactory(
        GoogCcFactoryConfig(rfc8888FeedbackNegotiated = rfc8888FeedbackNegotiated, screamMode = screamMode)
    ).create(config())

    init {
        context("The factory") {
            should("create Google CC by default") {
                GoogCcNetworkControllerFactory().create(config()).shouldBeInstanceOf<GoogCcNetworkController>()
            }
            should("create Google CC when SCReAM is never used") {
                create(ScreamMode.GoogCc, false).shouldBeInstanceOf<GoogCcNetworkController>()
                create(ScreamMode.GoogCc, true).shouldBeInstanceOf<GoogCcNetworkController>()
            }
            should("create SCReAM only with RFC 8888 feedback when that is the mode") {
                create(ScreamMode.ScreamWithRfc8888, false).shouldBeInstanceOf<GoogCcNetworkController>()
                create(ScreamMode.ScreamWithRfc8888, true).shouldBeInstanceOf<ScreamNetworkController>()
            }
            should("create SCReAM with either feedback when it is always used") {
                create(ScreamMode.ScreamWithTwccOrRfc8888, false).shouldBeInstanceOf<ScreamNetworkController>()
                create(ScreamMode.ScreamWithTwccOrRfc8888, true).shouldBeInstanceOf<ScreamNetworkController>()
            }
        }
        context("Only SCReAM") {
            should("say that it adapts to ECN") {
                create(ScreamMode.GoogCc, true).supportsEcnAdaptation() shouldBe false
                create(ScreamMode.ScreamWithRfc8888, true).supportsEcnAdaptation() shouldBe true
            }
        }
        context("The mode") {
            should("be read from its configuration value") {
                ScreamMode.fromConfigValue("never") shouldBe ScreamMode.GoogCc
                ScreamMode.fromConfigValue("with-rfc8888") shouldBe ScreamMode.ScreamWithRfc8888
                ScreamMode.fromConfigValue("always") shouldBe ScreamMode.ScreamWithTwccOrRfc8888
                shouldThrow<IllegalArgumentException> { ScreamMode.fromConfigValue("sometimes") }
            }
        }
    }
}

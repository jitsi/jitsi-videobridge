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

package org.jitsi.videobridge.load_management

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.JvbLastN
import java.util.function.Supplier

class LastNReducerTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val jvbLastN = spyk<JvbLastN>()
    val reductionScale = .5

    context("when a bridge has no conferences") {
        val reducer = LastNReducer(Supplier { emptyList<Conference>() }, jvbLastN, reductionScale)
        context("running the reducer") {
            reducer.reduceLoad()
            should("not set a last-n value") {
                verify(exactly = 0) { jvbLastN setProperty "jvbLastN" value any<Int>() }
            }
        }
    }
    context("when a bridge has conferences") {
        val conf1 = createMockConference(4, 8, 12)
        val conf2 = createMockConference(2, 4, 10)

        val reducer = LastNReducer(Supplier { listOf(conf1, conf2) }, jvbLastN, reductionScale)
        context("running the reducer") {
            reducer.reduceLoad()
            should("set the right last-n value") {
                // The highest forwarded count was 12, and the reduction factor was .5, so
                // it should be set to 6
                verify(exactly = 1) { jvbLastN setProperty "jvbLastN" value 6 }
            }
        }
        context("and no jvb last-n has been set") {
            context("running recovery") {
                reducer.recover()
                should("not alter the last-n value") {
                    verify(exactly = 0) { jvbLastN setProperty "jvbLastN" value any<Int>() }
                }
            }
        }
        context("and a jvb last-n has been set") {
            jvbLastN.jvbLastN = 4
            context("running recovery") {
                reducer.recover()
                should("increase the jvb last-n value") {
                    verify(exactly = 1) { jvbLastN setProperty "jvbLastN" value 8 }
                }
            }
        }
    }
})

/**
 * Create a mock [Conference] which has enpoints with the given number of forwarded video streams
 */
private fun createMockConference(vararg epNumForwardedVideo: Int): Conference {
    val eps = epNumForwardedVideo.map {
        mockk<Endpoint> { every { numForwardedEndpoints() } returns it }
    }.toList<AbstractEndpoint>()
    return mockk {
        every { endpoints } returns eps
    }
}

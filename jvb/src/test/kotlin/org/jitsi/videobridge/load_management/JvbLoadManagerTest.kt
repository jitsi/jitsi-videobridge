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
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.mins
import org.jitsi.utils.secs

class JvbLoadManagerTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val reducer = mockk<JvbLoadReducer>(relaxed = true) {
        every { impactTime() } returns 10.secs
    }
    val clock = FakeClock()

    val loadManager = JvbLoadManager<MockLoadMeasurement>(
        MockLoadMeasurement(10.0),
        MockLoadMeasurement(7.0),
        reducer,
        clock
    )

    context("a load update") {
        context("with a load which isn't overloaded") {
            loadManager.loadUpdate(MockLoadMeasurement(9.0))
            should("not trigger any action") {
                verify { reducer wasNot Called }
            }
        }

        context("with a load which is overloaded") {
            loadManager.loadUpdate(MockLoadMeasurement(10.0))
            should("trigger a call to reduce load") {
                verify(exactly = 1) { reducer.reduceLoad() }
            }
            context("followed by another load update") {
                context("with a load which is overloaded") {
                    val newLoad = MockLoadMeasurement(10.0)
                    context("before the impact time has elapsed") {
                        loadManager.loadUpdate(newLoad)
                        should("not trigger another call to reduce load") {
                            verify(exactly = 1) { reducer.reduceLoad() }
                        }
                    }
                    context("after the impact time has elapsed") {
                        clock.elapse(1.mins)
                        loadManager.loadUpdate(newLoad)
                        should("trigger another call to reduce load") {
                            verify(exactly = 2) { reducer.reduceLoad() }
                        }
                    }
                }
                context("with a load which isn't overloaded, but isn't under the recovery threshold") {
                    clock.elapse(1.mins)
                    loadManager.loadUpdate(MockLoadMeasurement(9.0))
                    should("not trigger any new calls") {
                        verify(exactly = 1) { reducer.reduceLoad() }
                        verify(exactly = 0) { reducer.recover() }
                    }
                }
                context("with a load below the recovery threshold") {
                    val newLoad = MockLoadMeasurement(2.0)
                    context("before the impact time has elapsed") {
                        loadManager.loadUpdate(newLoad)
                        should("not trigger any new calls") {
                            verify(exactly = 1) { reducer.reduceLoad() }
                            verify(exactly = 0) { reducer.recover() }
                        }
                    }
                    context("after the impact time has elapsed") {
                        clock.elapse(1.mins)
                        loadManager.loadUpdate(newLoad)
                        should("trigger a call to recover") {
                            verify(exactly = 1) { reducer.reduceLoad() }
                            verify(exactly = 1) { reducer.recover() }
                        }
                    }
                }
            }
        }
    }
})

class MockLoadMeasurement(var loadMeasurement: Double) : JvbLoadMeasurement {
    override fun getLoad(): Double = loadMeasurement

    override fun toString(): String = "Mock load measurement of $loadMeasurement"
}

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
package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.time.FakeClock
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService

/** Test scenario time controller,
 * based on WebRTC api/test/time_controller.{h,cc} in
 * and test/time_controller/simulated_time_controller.{h,cc}
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

interface TimeController {
    abstract fun getClock(): Clock

    abstract fun getScheduledExecutorService(): ScheduledExecutorService

    abstract fun advanceTime(duration: Duration)
}

class GlobalSimulatedTimeController: TimeController {
    override fun getClock(): Clock = clock

    override fun getScheduledExecutorService(): ScheduledExecutorService  = executorService

    override fun advanceTime(duration: Duration) = executorService.runUntil(clock.instant() + duration)

    private val clock = FakeClock()
    private val executorService = FakeScheduledExecutorService(clock)
}
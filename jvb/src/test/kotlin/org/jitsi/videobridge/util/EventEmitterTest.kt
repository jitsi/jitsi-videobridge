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

package org.jitsi.videobridge.util

import io.kotest.core.spec.style.ShouldSpec
import io.mockk.mockk
import io.mockk.verify

class EventEmitterTest : ShouldSpec({
    val emitter = EventEmitter<EventHandler>()

    context("registering handlers") {
        val handlerOne = mockk<EventHandler>(relaxed = true)
        val handlerTwo = mockk<EventHandler>(relaxed = true)
        emitter.addHandler(handlerOne)
        emitter.addHandler(handlerTwo)

        context("and then firing an event") {
            emitter.fireEvent { intEvent(42) }
            should("notify all handlers") {
                verify(exactly = 1) { handlerOne.intEvent(42) }
                verify(exactly = 1) { handlerTwo.intEvent(42) }
            }
        }
        context("and then removing a handler") {
            emitter.removeHandler(handlerOne)
            context("and then firing an event") {
                emitter.fireEvent { stringEvent("hello") }
                should("notify only the currently-registered handlers") {
                    verify(exactly = 1) { handlerTwo.stringEvent("hello") }
                    verify(exactly = 0) { handlerOne.stringEvent(any()) }
                }
            }
        }
    }
})

private interface EventHandler {
    fun intEvent(value: Int)
    fun stringEvent(value: String)
}

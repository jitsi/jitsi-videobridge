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

package org.jitsi.nlj.util

import io.kotlintest.matchers.collections.shouldContainExactly
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

class OrderedJsonObjectTest : ShouldSpec() {

    init {
        "an ordered json object" {
            val ojo = OrderedJsonObject()
            ojo["one"] = 1
            ojo["two"] = 2
            ojo["three"] = 3
            ojo["four"] = 4
            ojo["five"] = 5
            ojo["six"] = 6

            should("print items in the order they were added") {
                ojo.toJSONString() shouldBe "{\"one\":1,\"two\":2,\"three\":3,\"four\":4,\"five\":5,\"six\":6}"
            }

            should("iterate in the order they were added") {
                ojo.keys shouldContainExactly mutableSetOf("one", "two", "three", "four", "five", "six")
                ojo.values shouldContainExactly mutableSetOf(1, 2, 3, 4, 5, 6)
            }
        }
    }
}

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

package org.jitsi.nlj.format

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class PayloadTypeEncodingTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "createFrom" {
            should("be case insensitive") {
                val encoding = PayloadTypeEncoding.createFrom("vp8")
                val encoding2 = PayloadTypeEncoding.createFrom("vP8")
                val encoding3 = PayloadTypeEncoding.createFrom("Vp8")

                encoding shouldBe PayloadTypeEncoding.VP8
                encoding2 shouldBe PayloadTypeEncoding.VP8
                encoding3 shouldBe PayloadTypeEncoding.VP8

                encoding shouldBe encoding2
                encoding shouldBe encoding3
                encoding2 shouldBe encoding3
            }
            should("return OTHER in the invalid case") {
                PayloadTypeEncoding.createFrom("blah") shouldBe PayloadTypeEncoding.OTHER
            }
        }
    }
}

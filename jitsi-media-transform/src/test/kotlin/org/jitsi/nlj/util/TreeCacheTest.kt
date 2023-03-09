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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class TreeCacheTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    data class Dummy(val data: String)

    private val treeCache = TreeCache<Dummy>(16)

    init {
        context("Reading from an empty TreeCache") {
            should("return null") {
                treeCache.getValueBefore(10) shouldBe null
            }
        }
        context("A value in a TreeCache") {
            treeCache.insert(5, Dummy("A"))
            should("be found looking up values after it") {
                treeCache.getValueBefore(10) shouldBe Dummy("A")
            }
            should("be found looking up the same value") {
                treeCache.getValueBefore(5) shouldBe Dummy("A")
            }
            should("not be found looking up values before it") {
                treeCache.getValueBefore(3) shouldBe null
            }
            should("not be expired even if values long after it are looked up") {
                treeCache.getValueBefore(10000) shouldBe Dummy("A")
            }
        }
        context("Multiple values in a TreeCache") {
            treeCache.insert(5, Dummy("A"))
            treeCache.insert(10, Dummy("B"))
            should("Be looked up properly") {
                treeCache.getValueBefore(13) shouldBe Dummy("B")
            }
            should("Persist within the cache window") {
                treeCache.getValueBefore(8) shouldBe Dummy("A")
            }
            should("Expire only older ones if values outside the cache window are looked up") {
                treeCache.getValueBefore(25) shouldBe Dummy("B")
                treeCache.getValueBefore(8) shouldBe null
            }
            should("Expire only older ones when later values are inserted") {
                treeCache.insert(40, Dummy("C"))
                treeCache.getValueBefore(13) shouldBe null
            }
        }
    }
}

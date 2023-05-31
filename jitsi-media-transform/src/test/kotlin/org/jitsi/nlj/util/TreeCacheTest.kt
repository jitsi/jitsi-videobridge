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
            should("have size 0") {
                treeCache.size shouldBe 0
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
            should("cause the tree to have size 1") {
                treeCache.size shouldBe 1
            }
        }
        context("Multiple values in a TreeCache") {
            treeCache.insert(5, Dummy("A"))
            treeCache.insert(10, Dummy("B"))
            should("Be looked up properly") {
                treeCache.getValueBefore(13) shouldBe Dummy("B")
                treeCache.size shouldBe 2
            }
            should("Persist within the cache window") {
                treeCache.getValueBefore(8) shouldBe Dummy("A")
                treeCache.size shouldBe 2
            }
            should("Not expire an older one if it is the only value outside the cache window") {
                treeCache.getValueBefore(25) shouldBe Dummy("B")
                treeCache.getValueBefore(8) shouldBe Dummy("A")
                treeCache.size shouldBe 2
            }
            should("Expire older ones when newer ones are outside the cache window") {
                treeCache.getValueBefore(30) shouldBe Dummy("B")
                treeCache.getValueBefore(8) shouldBe null
                treeCache.size shouldBe 1
            }
            should("Expire only older ones when later values are inserted") {
                treeCache.insert(40, Dummy("C"))
                treeCache.getValueBefore(13) shouldBe Dummy("B")
                treeCache.getValueBefore(8) shouldBe null
                treeCache.size shouldBe 2
            }
            should("Persist values within the window while expiring values outside it") {
                treeCache.insert(15, Dummy("C"))
                treeCache.getValueBefore(8) shouldBe Dummy("A")
                treeCache.getValueBefore(25) shouldBe Dummy("C")
                treeCache.getValueBefore(13) shouldBe Dummy("B")
                treeCache.getValueBefore(8) shouldBe Dummy("A")
                treeCache.size shouldBe 3
                treeCache.insert(30, Dummy("D"))
                treeCache.getValueBefore(8) shouldBe null
                treeCache.size shouldBe 3
            }
        }
    }
}

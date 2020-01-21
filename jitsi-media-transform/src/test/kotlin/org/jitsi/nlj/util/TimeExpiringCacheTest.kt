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

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.types.shouldBeSameInstanceAs
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import java.time.Duration
import java.time.Instant
import org.jitsi.nlj.test_utils.FakeClock

internal class TimeExpiringCacheTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    data class Dummy(val num: Int)

    private val fakeClock = FakeClock()

    private val timeExpiringCache = TimeExpiringCache<Int, Dummy>(
            Duration.ofSeconds(1),
            20,
            fakeClock
    )

    init {
        "adding data" {
            val data1 = Dummy(1)
            timeExpiringCache.insert(data1.num, data1)
            "and then retrieving it before the timeout has elapsed" {
                val retrievedData1 = timeExpiringCache.get(data1.num)
                should("return the data") {
                    retrievedData1 shouldNotBe null
                    retrievedData1 shouldBeSameInstanceAs data1
                }
            }
            "and then retrieving it after the timeout has elapsed" {
                fakeClock.elapse(Duration.ofSeconds(5))
                val retrievedData1 = timeExpiringCache.get(data1.num)
                // We don't prune on 'get', only on 'insert'
                should("return the data") {
                    retrievedData1 shouldNotBe null
                    retrievedData1 shouldBeSameInstanceAs data1
                }
            }
            "and then adding more data > timeout period later" {
                val data2 = Dummy(2)
                fakeClock.elapse(Duration.ofSeconds(5))
                timeExpiringCache.insert(data2.num, data2)
                "and then trying to retrieve the old data" {
                    val retrievedData1 = timeExpiringCache.get(data1.num)
                    should("return null") {
                        retrievedData1 shouldBe null
                    }
                }
            }
        }
        "adding lots of data over time" {
            for (i in 1..10) {
                timeExpiringCache.insert(i, Dummy(i))
                fakeClock.elapse(Duration.ofSeconds(1))
            }
            should("expire things properly") {
                // Only the last value should still be present
                for (i in 1..9) {
                    timeExpiringCache.get(i) shouldBe null
                }
                timeExpiringCache.get(10) shouldNotBe null
            }
        }
        "adding data at the same time" {
            for (i in 1..10) {
                timeExpiringCache.insert(i, Dummy(i))
            }
            "and then another > timeout period later" {
                fakeClock.elapse(Duration.ofSeconds(10))
                timeExpiringCache.insert(11, Dummy(11))
                should("expire all the old data") {
                    for (i in 1..10) {
                        timeExpiringCache.get(i) shouldBe null
                    }
                }
            }
        }
        "adding data out of order" {
            // Here we simulate data being inserted out of order (with relation
            // to their index value)
            timeExpiringCache.insert(1, Dummy(1))
            fakeClock.setTime(Instant.ofEpochMilli(400))
            timeExpiringCache.insert(3, Dummy(3))
            fakeClock.setTime(Instant.ofEpochMilli(900))
            timeExpiringCache.insert(2, Dummy(2))

            fakeClock.setTime(Instant.ofEpochMilli(1100))
            timeExpiringCache.insert(4, Dummy(4))
            should("only expire up to the first old index") {
                timeExpiringCache.get(1) shouldBe null
                timeExpiringCache.get(2) shouldNotBe null
                // Even though element 3 should be expired according to its insertion
                // time, element 2 was new enough that it shouldn't be expired, so
                // the cache will stop pruning when it hits element 2 (which is before
                // it hits element 3)
                timeExpiringCache.get(3) shouldNotBe null
                timeExpiringCache.get(4) shouldNotBe null
            }
        }
        "adding more than the maximum amount of elements" {
            for (i in 1..20) {
                timeExpiringCache.insert(i, Dummy(i))
            }
            should("remove the oldest indices") {
                for (i in 21..30) {
                    timeExpiringCache.insert(i, Dummy(i))
                }
                // 1 through 10 should not be gone...
                for (i in 1..10) {
                    timeExpiringCache.get(i) shouldBe null
                }
                // ...but 11 through 30 should still be there
                for (i in 11..30) {
                    timeExpiringCache.get(i) shouldNotBe null
                }
            }
        }
    }
}

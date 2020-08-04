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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.utils.secs
import java.time.Instant

internal class TimeExpiringCacheTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    data class Dummy(val num: Int)

    private val fakeClock = FakeClock()

    private val timeExpiringCache = TimeExpiringCache<Int, Dummy>(
            1.secs,
            20,
            fakeClock
    )

    init {
        context("adding data") {
            val data1 = Dummy(1)
            timeExpiringCache.insert(data1.num, data1)
            context("and then retrieving it before the timeout has elapsed") {
                val retrievedData1 = timeExpiringCache.get(data1.num)
                should("return the data") {
                    retrievedData1 shouldNotBe null
                    retrievedData1 shouldBeSameInstanceAs data1
                }
            }
            context("and then retrieving it after the timeout has elapsed") {
                fakeClock.elapse(5.secs)
                val retrievedData1 = timeExpiringCache.get(data1.num)
                // We don't prune on 'get', only on 'insert'
                should("return the data") {
                    retrievedData1 shouldNotBe null
                    retrievedData1 shouldBeSameInstanceAs data1
                }
            }
            context("and then adding more data > timeout period later") {
                val data2 = Dummy(2)
                fakeClock.elapse(5.secs)
                timeExpiringCache.insert(data2.num, data2)
                context("and then trying to retrieve the old data") {
                    val retrievedData1 = timeExpiringCache.get(data1.num)
                    should("return null") {
                        retrievedData1 shouldBe null
                    }
                }
            }
        }
        context("adding lots of data over time") {
            for (i in 1..10) {
                timeExpiringCache.insert(i, Dummy(i))
                fakeClock.elapse(1.secs)
            }
            should("expire things properly") {
                // Only the last value should still be present
                for (i in 1..9) {
                    timeExpiringCache.get(i) shouldBe null
                }
                timeExpiringCache.get(10) shouldNotBe null
            }
        }
        context("adding data at the same time") {
            for (i in 1..10) {
                timeExpiringCache.insert(i, Dummy(i))
            }
            context("and then another > timeout period later") {
                fakeClock.elapse(10.secs)
                timeExpiringCache.insert(11, Dummy(11))
                should("expire all the old data") {
                    for (i in 1..10) {
                        timeExpiringCache.get(i) shouldBe null
                    }
                }
            }
        }
        context("adding data out of order") {
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
        context("adding more than the maximum amount of elements") {
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

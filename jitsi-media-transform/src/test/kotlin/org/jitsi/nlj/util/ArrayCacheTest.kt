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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.jitsi.utils.ms
import org.jitsi.utils.time.FakeClock

internal class ArrayCacheTest : ShouldSpec() {

    data class Dummy(val index: Long)

    private val fakeClock = FakeClock()

    private val arrayCache = object : ArrayCache<Dummy>(10, { Dummy(it.index) }, clock = fakeClock) {
        var discarded = 0
        override fun discardItem(item: Dummy) {
            discarded++
        }

        /** Expose protected [updateTimeAdded] to tests */
        fun publicUpdateTimeAdded(index: Long, timeAdded: Long) {
            updateTimeAdded(index, timeAdded)
        }
    }

    init {
        val data1 = Dummy(100)
        val dataOlder = Dummy(98)
        val dataNewer = Dummy(101)
        val dataTooOld = Dummy(77)

        var numHits = 0
        var numMisses = 0
        var numInserts = 0
        var numOldInserts = 0

        context("adding and retrieving items ") {
            arrayCache.insertItem(data1, data1.index) shouldBe true
            arrayCache.getContainer(data1.index)!!.item shouldBe data1
            numInserts++
            numHits++
        }

        context("adding and retrieving older items ") {
            arrayCache.insertItem(dataOlder, dataOlder.index) shouldBe true
            arrayCache.getContainer(dataOlder.index)!!.item shouldBe dataOlder
            numInserts++
            numHits++
        }

        context("adding an item with an index which is too old, and retrieving existing data ") {
            arrayCache.insertItem(dataTooOld, dataTooOld.index) shouldBe false
            arrayCache.getContainer(data1.index)!!.item shouldBe data1
            arrayCache.getContainer(dataOlder.index)!!.item shouldBe dataOlder
            numOldInserts++
            numHits += 2
        }

        context("replacing the data at the latest index") {
            val otherData = Dummy(11111)
            arrayCache.insertItem(otherData, 100) shouldBe true
            arrayCache.getContainer(100)!!.item shouldBe otherData
            numInserts++
            numHits++
        }

        context("replacing the data at an older index") {
            val otherData = Dummy(22222)
            arrayCache.insertItem(otherData, 98) shouldBe true
            arrayCache.getContainer(98)!!.item shouldBe otherData
            numInserts++
            numHits++
        }

        context("adding and retrieving more data") {
            arrayCache.insertItem(dataNewer, dataNewer.index) shouldBe true
            arrayCache.getContainer(dataNewer.index)!!.item shouldBe dataNewer
            numInserts++
            numHits++

            for (i in 150L..200L) {
                arrayCache.insertItem(Dummy(i), i) shouldBe true
                numInserts++
            }
            arrayCache.getContainer(199)!!.item shouldBe Dummy(199)
            numHits++
        }

        context("iterate forEachDescending") {
            // This should iterate for 200..195. It should not touch the miss/hit count or
            var nextExpected = 200
            val lastExpected = 195
            arrayCache.forEachDescending {
                it.index shouldBe nextExpected
                nextExpected shouldBeGreaterThanOrEqual lastExpected
                nextExpected--
                nextExpected >= lastExpected
            }
        }
        context("retrieving rewritten data") {
            arrayCache.getContainer(data1.index) shouldBe null
            numMisses++
        }
        context("retrieving data with a newer index") {
            arrayCache.getContainer(1000) shouldBe null
            numMisses++
        }
        context("keeping track of statistics ") {
            arrayCache.numInserts shouldBe numInserts
            arrayCache.numOldInserts shouldBe numOldInserts
            arrayCache.numHits shouldBe numHits
            arrayCache.numMisses shouldBe numMisses
        }
        context("flush should discard all items") {
            arrayCache.discarded = 0
            arrayCache.flush()
            arrayCache.discarded shouldBe arrayCache.size
            arrayCache.getContainer(200) shouldBe null
        }
        context("time added should be correct") {
            val time1 = fakeClock.millis()
            arrayCache.insertItem(data1, data1.index) shouldBe true

            fakeClock.elapse(100.ms)
            val time2 = fakeClock.millis()
            arrayCache.getContainer(data1.index)!!.timeAdded shouldBe time1
            arrayCache.insertItem(dataOlder, dataOlder.index)

            fakeClock.elapse(100.ms)
            arrayCache.getContainer(dataOlder.index)!!.timeAdded shouldBe time2
            arrayCache.getContainer(data1.index)!!.timeAdded shouldBe time1

            fakeClock.elapse(100.ms)
            val time3 = fakeClock.millis()
            arrayCache.publicUpdateTimeAdded(dataOlder.index, time3)

            fakeClock.elapse(100.ms)
            arrayCache.getContainer(dataOlder.index)!!.timeAdded shouldBe time3
            arrayCache.getContainer(data1.index)!!.timeAdded shouldBe time1
        }
    }
}

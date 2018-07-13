/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.experiment

import java.lang.Thread.sleep
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

fun log(msg: String) {
    println("${Thread.currentThread().id}: $msg")
}

class Consumer(val id: Int, val executor: ExecutorService) {
    val queue = LinkedBlockingQueue<Int>()
    val finishedFuture = CompletableFuture<Unit>()

    fun start() {
        scheduleWork()
//        scheduleWorkBlocking()
    }

    private fun scheduleWorkBlocking() {
        executor.execute {
            var num = 0
            while (num != -1) {
                num = queue.take()
                sleep(1)
            }
            finishedFuture.complete(Unit)
        }
    }

    private fun scheduleWork() {
        executor.execute {
            val num = queue.take()
            sleep(1)
//            log("Consumer $id got value $num")
            if (num != -1) {
                scheduleWork()
            } else {
                finishedFuture.complete(Unit)
            }
        }
    }
}

class Producer(val consumers: Map<Int, Consumer>) {

    fun start() {
        var num = 0
        while (num < 100000) {
            val index = num % 3
//            log("Producer writing value $num to consumer $index")
            consumers.getValue(index).queue.add(num)
//            log("queue now has size ${consumers.getValue(index).queue.size}")
            num++
        }
        consumers.values.forEach { it.queue.add(-1) }
    }
}


fun main(args: Array<String>) {
    val executor = ThreadPoolExecutor(3, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, LinkedBlockingDeque<Runnable>())
//    val executor = Executors.newSingleThreadExecutor()
    val consumers = mapOf(
        0 to Consumer(0, executor),
        1 to Consumer(1, executor),
        2 to Consumer(2, executor)
    )
    consumers.values.forEach(Consumer::start)

    val producer = Producer(consumers)
    val time = measureTimeMillis {
        producer.start()
        consumers.values.forEach { it.finishedFuture.get() }
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }
    println("Took $time ms")
}

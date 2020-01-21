package org.jitsi.nlj.test_utils

import com.nhaarman.mockitokotlin2.spy
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.time.Duration
import java.util.concurrent.TimeUnit

class FakeSchedulerExecutorServiceTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    init {
        val executor: FakeScheduledExecutorService = spy()

        "Scheduling a recurring job" {
            var numJobRuns = 0
            val handle = executor.scheduleAtFixedRate({
                numJobRuns++
            }, 5, 5, TimeUnit.SECONDS)
            "and then calling runOne" {
                executor.runOne()
                should("have run the job") {
                    numJobRuns shouldBe 1
                }
                "and then calling runOne again" {
                    executor.runOne()
                    should("have run the job again") {
                        numJobRuns shouldBe 2
                    }
                }
                "and then cancelling the job" {
                    handle.cancel(true)
                    "and then calling runOne again" {
                        executor.runOne()
                        should("not have run the job again") {
                            numJobRuns shouldBe 1
                        }
                    }
                }
            }
            "and elapsing time before it should run" {
                executor.clock.elapse(Duration.ofSeconds(4))
                executor.run()
                should("not have run the job") {
                    numJobRuns shouldBe 0
                }
                "and then elapsing past the scheduled time" {
                    executor.clock.elapse(Duration.ofSeconds(3))
                    executor.run()
                    should("have run the job") {
                        numJobRuns shouldBe 1
                    }
                }
            }
            "and elapsing the time past multiple runs" {
                executor.clock.elapse(Duration.ofSeconds(10))
                executor.run()
                should("have run the job multiple times") {
                    numJobRuns shouldBe 2
                }
            }
        }
    }
}

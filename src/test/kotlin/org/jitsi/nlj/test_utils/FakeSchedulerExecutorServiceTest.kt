package org.jitsi.nlj.test_utils

import com.nhaarman.mockitokotlin2.spy
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.secs
import java.util.concurrent.TimeUnit

class FakeSchedulerExecutorServiceTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        val executor: FakeScheduledExecutorService = spy()

        context("Scheduling a recurring job") {
            var numJobRuns = 0
            val handle = executor.scheduleAtFixedRate({
                numJobRuns++
            }, 5, 5, TimeUnit.SECONDS)
            context("and then calling runOne") {
                executor.runOne()
                should("have run the job") {
                    numJobRuns shouldBe 1
                }
                context("and then calling runOne again") {
                    executor.runOne()
                    should("have run the job again") {
                        numJobRuns shouldBe 2
                    }
                }
                context("and then cancelling the job") {
                    handle.cancel(true)
                    context("and then calling runOne again") {
                        executor.runOne()
                        should("not have run the job again") {
                            numJobRuns shouldBe 1
                        }
                    }
                }
            }
            context("and elapsing time before it should run") {
                executor.clock.elapse(4.secs)
                executor.run()
                should("not have run the job") {
                    numJobRuns shouldBe 0
                }
                context("and then elapsing past the scheduled time") {
                    executor.clock.elapse(3.secs)
                    executor.run()
                    should("have run the job") {
                        numJobRuns shouldBe 1
                    }
                }
            }
            context("and elapsing the time past multiple runs") {
                executor.clock.elapse(10.secs)
                executor.run()
                should("have run the job multiple times") {
                    numJobRuns shouldBe 2
                }
            }
        }
    }
}

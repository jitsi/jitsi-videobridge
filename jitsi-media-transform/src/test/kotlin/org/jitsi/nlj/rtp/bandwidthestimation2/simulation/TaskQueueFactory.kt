package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import java.util.concurrent.ScheduledExecutorService

/** Test scenario call,
 * based on WebRTC api/task_queue/task_queue_factory.h in
 * WebRTC tag branch-heads/6422 (Chromium 125).
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */
abstract class TaskQueueFactory {
    abstract fun createTaskQueue(): ScheduledExecutorService
}

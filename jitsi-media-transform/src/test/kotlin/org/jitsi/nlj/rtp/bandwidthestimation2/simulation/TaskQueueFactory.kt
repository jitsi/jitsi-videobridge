package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import java.util.concurrent.ScheduledExecutorService

/** Test scenario call,
 * based on WebRTC api/task_queue/task_queue_factory.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */
abstract class TaskQueueFactory {
    abstract fun createTaskQueue(): ScheduledExecutorService
}

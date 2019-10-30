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

package org.jitsi.videobridge.util;

import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging2.*;
import org.json.simple.*;

import java.util.concurrent.*;

public class TaskPools
{
    private static final Logger classLogger = new LoggerImpl(TaskPools.class.getName());
    /**
     * A global executor service which can be used for non-CPU-intensive tasks.
     */
    public static ExecutorService IO_POOL =
            Executors.newCachedThreadPool(new NameableThreadFactory("Global IO pool"));

    /**
     * An executor to be used for CPU-intensive tasks.  NOTE that tasks which block should
     * NOT use this pool!
     */
    public static ExecutorService CPU_POOL =
            Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors(),
                    new NameableThreadFactory("Global CPU pool")
            );

    public static ScheduledExecutorService SCHEDULED_POOL =
            Executors.newSingleThreadScheduledExecutor(new NameableThreadFactory("Global scheduled pool"));

    public static JSONObject getStatsJson(ExecutorService es)
    {
        JSONObject debugState = new JSONObject();
        debugState.put("executor_class", es.getClass().getSimpleName());

        if (es instanceof ThreadPoolExecutor)
        {
            ThreadPoolExecutor ex = (ThreadPoolExecutor)es;
            debugState.put("pool_size", ex.getPoolSize());
            debugState.put("active_count", ex.getActiveCount());
            debugState.put("completed_tasks", ex.getCompletedTaskCount());
            debugState.put("core_pool_size", ex.getCorePoolSize());
            debugState.put("maximum_pool_size", ex.getMaximumPoolSize());
            debugState.put("largest_pool_size", ex.getLargestPoolSize());
            debugState.put("queue_class", ex.getQueue().getClass().getSimpleName());
            debugState.put("queue_size", ex.getQueue().size());
        }

        return debugState;
    }

    public static JSONObject getStatsJson()
    {
        JSONObject debugState = new JSONObject();

        debugState.put("IO_POOL", getStatsJson(IO_POOL));
        debugState.put("CPU_POOL", getStatsJson(CPU_POOL));

        return debugState;
    }

    static {
        classLogger.info("TaskPools detected " + Runtime.getRuntime().availableProcessors() +
                " processors, creating the CPU pool with that many threads");

    }
}

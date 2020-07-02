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
import java.util.concurrent.atomic.*;

public class TaskPools
{
    private static final Logger classLogger = new LoggerImpl(TaskPools.class.getName());

    /**
     * Count and log exceptions from the IO pool.
     */
    private static final ExceptionLogger IO_POOL_EXCEPTION_LOGGER = new ExceptionLogger("Global IO pool");

    /**
     * A global executor service which can be used for non-CPU-intensive tasks.
     */
    public static final ExecutorService IO_POOL =
        new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new NameableThreadFactory("Global IO pool"))
        {
            @Override
            protected void afterExecute(Runnable runnable, Throwable t)
            {
                super.afterExecute(runnable, t);
                IO_POOL_EXCEPTION_LOGGER.checkForExceptionAndLog(runnable, t);
            }
        };

    /**
     * Count and log exceptions from the CPU pool.
     */
    private static final ExceptionLogger CPU_POOL_EXCEPTION_LOGGER = new ExceptionLogger("CPU pool");

    /**
     * An executor to be used for CPU-intensive tasks.  NOTE that tasks which block should
     * NOT use this pool!
     */
    public static final ExecutorService CPU_POOL =
        new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().availableProcessors(),
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>())
        {
            @Override
            protected void afterExecute(Runnable runnable, Throwable t)
            {
                super.afterExecute(runnable, t);
                CPU_POOL_EXCEPTION_LOGGER.checkForExceptionAndLog(runnable, t);
            }
        };

    /**
     * Count and log exceptions from the scheduled executor.
     */
    private static final ExceptionLogger SCHEDULED_POOL_EXCEPTION_LOGGER = new ExceptionLogger("Global scheduled pool");

    public static final ScheduledExecutorService SCHEDULED_POOL =
        new ScheduledThreadPoolExecutor(1, new NameableThreadFactory("Global scheduled pool"))
        {
            @Override
            protected void afterExecute(Runnable runnable, Throwable t)
            {
                super.afterExecute(runnable, t);
                SCHEDULED_POOL_EXCEPTION_LOGGER.checkForExceptionAndLog(runnable, t);
            }

        };

    @SuppressWarnings("unchecked")
    public static JSONObject getStatsJson(ExecutorService es)
    {
        JSONObject debugState = new JSONObject();
        debugState.put("executor_class", es.getClass().getSimpleName());

        if (es instanceof ThreadPoolExecutor)
        {
            ThreadPoolExecutor ex = (ThreadPoolExecutor)es;
            debugState.put("pool_size", ex.getPoolSize());
            debugState.put("active_task_count", ex.getActiveCount());
            debugState.put("completed_task_count", ex.getCompletedTaskCount());
            debugState.put("core_pool_size", ex.getCorePoolSize());
            debugState.put("maximum_pool_size", ex.getMaximumPoolSize());
            debugState.put("largest_pool_size", ex.getLargestPoolSize());
            debugState.put("queue_class", ex.getQueue().getClass().getSimpleName());
            debugState.put("pending_task_count", ex.getQueue().size());
        }

        return debugState;
    }

    @SuppressWarnings("unchecked")
    public static JSONObject getStatsJson()
    {
        JSONObject stats = new JSONObject();

        JSONObject ioPoolStats = getStatsJson(IO_POOL);
        ioPoolStats.put("num_exceptions", IO_POOL_EXCEPTION_LOGGER.numExceptions.get());
        stats.put("IO_POOL", ioPoolStats);

        JSONObject cpuPoolStats = getStatsJson(CPU_POOL);
        cpuPoolStats.put("num_exceptions", CPU_POOL_EXCEPTION_LOGGER.numExceptions.get());
        stats.put("CPU_POOL", cpuPoolStats);

        JSONObject scheduledPoolStats = getStatsJson(CPU_POOL);
        cpuPoolStats.put("num_exceptions", SCHEDULED_POOL_EXCEPTION_LOGGER.numExceptions.get());
        stats.put("SCHEDULED_POOL", scheduledPoolStats);

        return stats;
    }

    /**
     * Count and log exceptions from an {@ExecutorService}.
     */
    private static class ExceptionLogger
    {
        private final AtomicLong numExceptions = new AtomicLong();
        private final String name;

        private ExceptionLogger(String name)
        {
            this.name = name;
        }

        /**
         * See {@link ThreadPoolExecutor#afterExecute(Runnable, Throwable)}
         * @param runnable
         * @param t
         */
        private void checkForExceptionAndLog(Runnable runnable, Throwable t)
        {
            if (t == null && runnable instanceof Future<?>)
            {
                Future<?> future = (Future<?>) runnable;
                try
                {
                    if (future.isDone() || future.isCancelled())
                    {
                        future.get();
                    }
                }
                catch (CancellationException ce)
                {
                    // Ignore these. Notably PacketQueue AsyncQueueHandler causes a lot of them (for the RtpReceiver,
                    // but not RtpSender).
                }
                catch (ExecutionException ee)
                {
                    t = ee.getCause();
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                }
            }

            if (t != null)
            {
                numExceptions.incrementAndGet();
                classLogger.warn("Uncaught exception (" + name + "): ", t);
            }
        }
    }

    static
    {
        classLogger.info("TaskPools detected " + Runtime.getRuntime().availableProcessors() +
                " processors, creating the CPU pool with that many threads");
    }
}

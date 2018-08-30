/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.util.concurrent;

import java.util.*;
import java.util.concurrent.*;

import org.jitsi.util.*;

/**
 * Implements a single-threaded {@link Executor} of
 * {@link RecurringRunnable}s i.e. asynchronous tasks which determine by
 * themselves the intervals (the lengths of which may vary) at which they are to
 * be invoked.
 *
 * webrtc/modules/utility/interface/process_thread.h
 * webrtc/modules/utility/source/process_thread_impl.cc
 * webrtc/modules/utility/source/process_thread_impl.h
 *
 * @author Lyubomir Marinov
 * @author George Politis
 */
public class RecurringRunnableExecutor
        implements Executor
{
    /**
     * The <tt>Logger</tt> used by the <tt>RecurringRunnableExecutor</tt>
     * class and its instances to print debug information.
     */
//    private static final Logger logger
//            = Logger.getLogger(RecurringRunnableExecutor.class);

    /**
     * The {@code RecurringRunnable}s registered with this instance which are
     * to be invoked in {@link #thread}.
     */
    private final List<RecurringRunnable> recurringRunnables
            = new LinkedList<>();

    /**
     * The (background) {@code Thread} which invokes
     * {@link RecurringRunnable#run()} on {@link #recurringRunnables}
     * (in accord with their respective
     * {@link RecurringRunnable#getTimeUntilNextRun()}).
     */
    private Thread thread;

    /**
     * A {@code String} which will be added to the name of {@link #thread}.
     * Meant to facilitate debugging.
     */
    private final String name;

    /**
     * Whether this {@link RecurringRunnableExecutor} is closed. When it is
     * closed, it should stop its thread(s).
     */
    private boolean closed = false;

    /**
     * Initializes a new {@link RecurringRunnableExecutor} instance.
     */
    public RecurringRunnableExecutor()
    {
        this(/* name */ "");
    }

    /**
     * Initializes a new {@link RecurringRunnableExecutor} instance.
     * @param name a string to be added to the name of the thread which this
     * instance will start.
     */
    public RecurringRunnableExecutor(String name)
    {
        this.name = name;
    }

    /**
     * De-registers a {@code RecurringRunnable} from this {@code Executor} so
     * that its {@link RecurringRunnable#run()} is no longer invoked (by
     * this instance).
     *
     * @param recurringRunnable the {@code RecurringRunnable} to
     * de-register from this instance
     * @return {@code true} if the list of {@code RecurringRunnable}s of this
     * instance changed because of the method call; otherwise, {@code false}
     */
    public boolean deRegisterRecurringRunnable(
            RecurringRunnable recurringRunnable)
    {
        if (recurringRunnable == null)
        {
            return false;
        }
        else
        {
            synchronized (recurringRunnables)
            {
                boolean removed
                        = recurringRunnables.remove(recurringRunnable);

                if (removed)
                    startOrNotifyThread();
                return removed;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Accepts for execution {@link RecurringRunnable}s only.
     */
    @Override
    public void execute(Runnable command)
    {
        Objects.requireNonNull(command, "command");

        if (!(command instanceof RecurringRunnable))
        {
            throw new RejectedExecutionException(
                    "The class " + command.getClass().getName()
                            + " of command does not implement "
                            + RecurringRunnable.class.getName());
        }

        registerRecurringRunnable((RecurringRunnable) command);
    }

    /**
     * Executes an iteration of the loop implemented by {@link #runInThread()}.
     * Invokes {@link RecurringRunnable#run()} on all
     * {@link #recurringRunnables} which are at or after the time at which
     * they want the method in question called.
     * TODO(brian): worth investigating if we can invoke the ready runnables
     * outside the scope of the {@link RecurringRunnableExecutor#recurringRunnables}
     * lock so we can get rid of a possible deadlock scenario when invoking
     * a runnable which needs to signal to the executor that it has work ready
     *
     * @return {@code true} to continue with the next iteration of the loop
     * implemented by {@link #runInThread()} or {@code false} to break (out of)
     * the loop
     */
    private boolean run()
    {
        if (closed || !Thread.currentThread().equals(thread))
        {
            return false;
        }

        // Wait for the recurringRunnable that should be called next, but
        // don't block thread longer than 100 ms.
        long minTimeToNext = 100L;

        synchronized (recurringRunnables)
        {
            if (recurringRunnables.isEmpty())
            {
                return false;
            }
            for (RecurringRunnable recurringRunnable
                    : recurringRunnables)
            {
                long timeToNext
                        = recurringRunnable.getTimeUntilNextRun();

                if (minTimeToNext > timeToNext)
                    minTimeToNext = timeToNext;
            }
        }

        if (minTimeToNext > 0L)
        {
            synchronized (recurringRunnables)
            {
                if (recurringRunnables.isEmpty())
                {
                    return false;
                }
                try
                {
                    recurringRunnables.wait(minTimeToNext);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
        }
        synchronized (recurringRunnables)
        {
            for (RecurringRunnable recurringRunnable
                    : recurringRunnables)
            {
                long timeToNext
                        = recurringRunnable.getTimeUntilNextRun();

                if (timeToNext < 1L)
                {
                    try
                    {
                        recurringRunnable.run();
                    }
                    catch (Throwable t)
                    {
                        if (t instanceof InterruptedException)
                        {
                            Thread.currentThread().interrupt();
                        }
                        else if (t instanceof ThreadDeath)
                        {
                            throw (ThreadDeath) t;
                        }
                        else
                        {
//                            logger.error(
//                                    "The invocation of the method "
//                                            + recurringRunnable
//                                            .getClass().getName()
//                                            + ".run() threw an exception.",
//                                    t);
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Registers a {@code RecurringRunnable} with this {@code Executor} so
     * that its {@link RecurringRunnable#run()} is invoked (by this
     * instance).
     *
     * @param recurringRunnable the {@code RecurringRunnable} to register
     * with this instance
     * @return {@code true} if the list of {@code RecurringRunnable}s of this
     * instance changed because of the method call; otherwise, {@code false}
     */
    public boolean registerRecurringRunnable(
            RecurringRunnable recurringRunnable)
    {
        Objects.requireNonNull(recurringRunnable, "recurringRunnable");

        synchronized (recurringRunnables)
        {
            if (closed)
            {
                return false;
            }

            // Only allow recurringRunnable to be registered once.
            if (recurringRunnables.contains(recurringRunnable))
            {
                return false;
            }
            else
            {
                recurringRunnables.add(0, recurringRunnable);

                // Wake the thread calling run() to update the waiting
                // time. The waiting time for the just registered
                // recurringRunnable may be shorter than all other
                // registered recurringRunnables.
                startOrNotifyThread();
                return true;
            }
        }
    }

    /**
     * Runs in {@link #thread}.
     */
    private void runInThread()
    {
        try
        {
            while (run());
        }
        finally
        {
            synchronized (recurringRunnables)
            {
                if (!closed && Thread.currentThread().equals(thread))
                {
                    thread = null;
                    // If the (current) thread dies in an unexpected way, make
                    // sure that a new thread will replace it if necessary.
                    startOrNotifyThread();
                }
            }
        }
    }

    /**
     * Starts or notifies {@link #thread} depending on and in accord with the
     * state of this instance.
     */
    public void startOrNotifyThread()
    {
        synchronized (recurringRunnables)
        {
            if (!closed && this.thread == null)
            {
                if (!recurringRunnables.isEmpty())
                {
                    Thread thread
                            = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            RecurringRunnableExecutor.this
                                    .runInThread();
                        }
                    };

                    thread.setDaemon(true);
                    thread.setName(
                            RecurringRunnableExecutor.class.getName()
                                    + ".thread-" + name);

                    boolean started = false;

                    this.thread = thread;
                    try
                    {
                        thread.start();
                        started = true;
                    }
                    finally
                    {
                        if (!started && thread.equals(this.thread))
                            this.thread = null;
                    }
                }
            }
            else
            {
                recurringRunnables.notifyAll();
            }
        }
    }

    /**
     * Closes this {@link RecurringRunnableExecutor}, signalling its thread to
     * stop and de-registering all registered runnables.
     */
    public void close()
    {
        synchronized (recurringRunnables)
        {
            closed = true;
            thread = null;
            recurringRunnables.notifyAll();
        }
    }
}

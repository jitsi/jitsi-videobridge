/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.utils.logging2.*;

import java.lang.ref.*;
import java.util.*;

/**
 * An implementation of {@link Expireable} which allows a single thread to do
 * the actual work, and returns immediately if there is another thread
 * already executing {@link #safeExpire()}.
 *
 * @author Boris Grozev
 */
public class ExpireableImpl
    implements Expireable
{
    /**
     * Gets a {@link String} representation of a {@link Thread}'s stack trace
     * to be used for logging.
     * @param thread the thread to get the stack trace from (or null).
     * @return a {@link String} representation of a {@link Thread}'s stack trace
     * to be used for logging.
     */
    private static String getStackTraceAsString(Thread thread)
    {
        if (thread == null)
        {
            return "null";
        }

        return Arrays.stream(thread.getStackTrace())
            .map(StackTraceElement::toString)
            .reduce((s, str) -> s.concat(" -> ").concat(str))
            .orElse("empty");
    }

    /**
     * The {@link Logger} to be used by the {@link ExpireableImpl} class
     * and its instances to print debug information.
     */
    private final Logger logger;

    /**
     * A weak reference to the thread currently running {@link #safeExpire()}
     * (if any).
     */
    private WeakReference<Thread> expireThread = null;

    /**
     * The time at which the thread currently running {@link #safeExpire()}
     * started to execute {@link #safeExpire()}, or {@code -1} if there is no
     * thread currently running {@link #safeExpire()}
     */
    private long expireStarted = -1;

    /**
     * The object used to synchronize access to {@link #expireStarted} and
     * {@link #expireThread}.
     */
    private final Object syncRoot = new Object();

    /**
     * The {@link Runnable} which will do the actual expiration.
     */
    private final Runnable expireRunnable;

    /**
     * Initializes a new {@link ExpireableImpl} instance.
     * @param parentLogger the parent logger from which this instance can create its own logger
     * @param expireRunnable the {@link Runnable} to execute.
     */
    public ExpireableImpl(Logger parentLogger, Runnable expireRunnable)
    {
        this.logger = parentLogger.createChildLogger(ExpireableImpl.class.getName());
        this.expireRunnable = Objects.requireNonNull(expireRunnable);
    }

    /**
     * {@inheritDoc}
     * </p>
     * A default implementation which always returns {@code false}.
     * @return always {@code false}.
     */
    @Override
    public boolean shouldExpire()
    {
        return false;
    }

    /**
     * {@inheritDoc}
     * </p>
     * If another thread is found to be already running {@link #safeExpire()},
     * returns immediately (and prints a message if the thread has been running
     * for a prolonged period).
     */
    @Override
    public void safeExpire()
    {
        synchronized (syncRoot)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Expiring");
            }

            long now = System.currentTimeMillis();
            if (expireStarted > 0)
            {

                // Another thread is already in charge of running doExpire().
                // We are not going to run doExpire(), but before we return lets
                // check and log a message if the other thread has been running
                // for a long time.
                long duration = now - expireStarted;

                if (duration > 1000 || logger.isDebugEnabled())
                {
                    Thread expireThread
                        = this.expireThread == null
                            ? null : this.expireThread.get();
                    logger.warn(
                        "A thread has been running safeExpire() "
                            + "for " + duration +"ms: "
                            + getStackTraceAsString(expireThread));
                }

                return;
            }

            expireStarted = now;
            expireThread = new WeakReference<>(Thread.currentThread());
        }

        try
        {
            expireRunnable.run();
        }
        catch (Throwable t)
        {
            logger.error("Failed to expire ", t);
        }
        finally
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Expired ");
            }

            synchronized (syncRoot)
            {
                expireStarted = -1;
                expireThread = null;
            }
        }

    }
}

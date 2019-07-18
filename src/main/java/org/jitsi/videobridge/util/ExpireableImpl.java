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

import org.jitsi.utils.logging.*;

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
    private static final Logger logger
        = Logger.getLogger(ExpireableImpl.class);

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
     * A name for this {@link ExpireableImpl}, to be used for logging.
     */
    private final String name;

    /**
     * The {@link Runnable} which will do the actual expiration.
     */
    private final Runnable expireRunnable;

    /**
     * Initializes a new {@link ExpireableImpl} instance.
     * @param name the name of the {@link ExpireableImpl}, used for logging.
     * @param expireRunnable the {@link Runnable} to execute.
     */
    public ExpireableImpl(String name, Runnable expireRunnable)
    {
        this.name = name;
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
                logger.debug("Expiring " + name);
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
                        "A thread has been running safeExpire() on " + name
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
            logger.error("Failed to expire " + name, t);
        }
        finally
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Expired " + name);
            }

            synchronized (syncRoot)
            {
                expireStarted = -1;
                expireThread = null;
            }
        }

    }
}

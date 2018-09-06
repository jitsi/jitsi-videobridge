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

import java.util.concurrent.atomic.*;

/**
 * Enriches {@link AtomicLong} with methods that allow it to be updated only if
 * doing so would increase (or decrease) its value.
 *
 * @author Boris Grozev
 */
public class MonotonicAtomicLong extends AtomicLong
{
    /**
     * Updates the value of this {@link AtomicLong} if it is bigger than the
     * current value, and returns the actual new value.
     *
     * Implemented this way (without {@link #updateAndGet}) for compatibility
     * with java 1.7.
     *
     * @param newValue the new value to try to set.
     * @return the actual new value whuch may be greater than or equal to
     * {@code newValue}.
     */
    public long increase(final long newValue)
    {
        long prev, next;

        do
        {
            prev = get();
            next = Math.max(newValue, prev);
        }
        while (!compareAndSet(prev, next));

        return next;
    }

    /**
     * Updates the value of this {@link AtomicLong} if it is smaller than the
     * current value, and returns the actual new value.
     *
     * Implemented this way (without {@link #updateAndGet}) for compatibility
     * with java 1.7.
     *
     * @param newValue the value to try to set.
     * @return the actual new value which may be less than or equal to
     * {@code newValue}.
     */
    public long decrease(final long newValue)
    {
        long prev, next;

        do
        {
            prev = get();
            next = Math.min(newValue, prev);
        }
        while (!compareAndSet(prev, next));

        return next;
    }
}

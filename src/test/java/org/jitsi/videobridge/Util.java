/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge;

import java.util.function.*;

import static junit.framework.TestCase.assertEquals;

/**
 * Test utilities.
 *
 * @author Boris Grozev
 */
public class Util
{
    /**
     * Tries to take a nap, but isn't grumpy if it's woken up early.
     * @param millis the number of milliseconds to sleep.
     */
    public static void sleepNoExceptions(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException ie)
        {
        }
    }

    /**
     * Retries a function call ({@code f}) up to {@code attempts} times until
     * it produces the {@code expected} result, sleeping for
     * {@code millisToWait} in between attempts. If the function doesn't produce
     * the expected result after {@code attempts} attempts, throws an exception.
     *
     * @param millisToWait the number of milliseconds to wait in total.
     * @param attempts
     * @param message The message to throw in case of failure.
     * @param expected The expected result.
     * @param f The function which produces the actual result.
     */
    public static void waitForEquals(
        long millisToWait,
        int attempts,
        String message,
        Object expected,
        Supplier<Object> f)
    {
        Object actual;
        int i = 0;

        do {
            actual = f.get();
            if (expected.equals(actual))
            {
                return;
            }

            sleepNoExceptions(millisToWait);
            i++;
        } while (i < attempts);

        assertEquals(message, expected, actual);
    }

    /**
     * Retries a function call ({@code f}) up to 5 times until
     * it produces the {@code expected} result, sleeping for 500 milliseconds
     * in between attempts. If the function doesn't produce the expected result
     * after {@code attempts} attempts, throws an exception.
     *
     * @param message The message to throw in case of failure.
     * @param expected The expected result.
     * @param f The function which produces the actual result.
     */
    public static void waitForEquals(
        String message,
        Object expected,
        Supplier<Object> f)
    {
        waitForEquals(500, 5, message, expected, f);
    }
}

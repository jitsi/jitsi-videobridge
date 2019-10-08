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

/**
 * A helper class to validate/ensure that a supplied port
 * is a valid, non-privileged port.  {@link InvalidUnprivilegedPortException}
 * is thrown if the given port is not a valid, unprivileged port.
 */
public class UnprivilegedPort
{
    private final int port;

    public UnprivilegedPort(int portNum) throws InvalidUnprivilegedPortException
    {
        if ((portNum < 1024) || (portNum > 0xffff))
        {
            throw new InvalidUnprivilegedPortException("Valid, unprivileged port required, "
                + portNum + " is unacceptable");
        }
        this.port = portNum;
    }

    public int get()
    {
        return this.port;
    }

    public static class InvalidUnprivilegedPortException extends RuntimeException
    {
        public InvalidUnprivilegedPortException(String reason)
        {
            super(reason);
        }
    }
}

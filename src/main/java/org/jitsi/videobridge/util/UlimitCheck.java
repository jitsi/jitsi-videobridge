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

import java.io.*;

/**
 * Checks the `ulimit` values for the process and prints a warning message if
 * they are too low.
 *
 * @author Boris Grozev
 */
public class UlimitCheck
{
    /**
     * The {@link Logger} to be used by the {@link UlimitCheck} class
     * and its instances to print debug information.
     */
    private static final Logger logger = new LoggerImpl(UlimitCheck.class.getName());

    /**
     * Executes a command in {@code bash} and returns the output ({@code stdin}
     * and {@code stderr} combined), or {@code null} on failure.
     * @param command the command to execute.
     * @return the output of the command or {@code null}.
     */
    public static String getOutputFromCommand(String command)
    {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);

        try
        {
            Process p = pb.start();

            BufferedReader br
                = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String output =  br.lines().reduce(String::concat).orElse("null?");
            br.close();
            return output;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /**
     * Executes a command in {@code bash} and returns the output parsed as an
     * {@link Integer}, or {@code null} if it is not parsable as an integer.
     * @param command the command to execute.
     * @return the output of the command or {@code null}.
     */
    public static Integer getIntFromCommand(String command)
    {
        try
        {
            return Integer.parseInt(getOutputFromCommand(command));
        }
        catch (NumberFormatException n)
        {
            return null;
        }
    }

    /**
     * Extracts the current limits for number of open files and user processes
     * (threads) by running {@code bash}'s {@code ulimit} builtin, and logs
     * the values.
     */
    public static void printUlimits() {

        Integer fileLimit = getIntFromCommand("ulimit -n");
        Integer fileLimitHard = getIntFromCommand("ulimit -Hn");
        Integer threadLimit = getIntFromCommand("ulimit -u");
        Integer threadLimitHard = getIntFromCommand("ulimit -Hu");

        StringBuilder sb
            = new StringBuilder("Running with open files limit ")
                .append(fileLimit)
                .append(" (hard ").append(fileLimitHard).append(')')
                .append(", thread limit ").append(threadLimit)
                .append(" (hard ").append(threadLimitHard).append(").");

        // At the time of this writing these constants correspond to somewhere
        // around 250 simultaneous participants.
        boolean warn = fileLimit == null || fileLimit <= 4096 ||
            threadLimit == null || threadLimit <= 8192;

        if (warn)
        {
            sb.append(" These values are too low and they will limit the ")
                .append("number of participants that the bridge can serve ")
                .append("simultaneously.");
            logger.warn(sb);
        }
        else
        {
            logger.info(sb);
        }

    }
}

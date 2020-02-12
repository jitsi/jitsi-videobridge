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
package org.jitsi.videobridge.stats;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.*;
import java.util.*;
import java.util.function.*;

public class JvmStats
    extends Statistics
{
    public static final String CPU_USAGE = "cpu_usage";

    private final Collection<Consumer<Float>> cpuUsageConsumers = new ArrayList<>();

    private long prevProcessCpuTime;

    private long prevUpTime;

    @Override
    public void generate()
    {
        OperatingSystemMXBean operatingSystemMXBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        int availableProcessors = operatingSystemMXBean.getAvailableProcessors();
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

        long upTime = runtimeMXBean.getUptime();
        long processCpuTime = operatingSystemMXBean.getProcessCpuTime();

        long processCpuTimeDelta = processCpuTime - prevProcessCpuTime;
        long cpuTimeDelta = upTime - prevUpTime;

        this.prevProcessCpuTime = processCpuTime;
        this.prevUpTime = upTime;

        float cpuUsage = processCpuTimeDelta / (cpuTimeDelta * 10_000F * availableProcessors);
        unlockedSetStat(CPU_USAGE, cpuUsage);

        for (Consumer<Float> cpuUsageConsumer : cpuUsageConsumers)
        {
            cpuUsageConsumer.accept(cpuUsage);
        }
    }

    public void addCpuUsageConsumer(Consumer<Float> cpuUsageConsumer)
    {
        cpuUsageConsumers.add(cpuUsageConsumer);
    }
}

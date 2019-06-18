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

import java.lang.management.*;
import java.lang.reflect.*;

import org.hyperic.sigar.*;
import org.hyperic.sigar.cmd.*;
import org.jitsi.utils.logging.*;

/**
 * Implements retrieving statistics from OS such as total physical memory size
 * and CPU usage.
 *
 * @author Hristo Terezov
 */
public class OsStatistics
{
    /**
     * The <tt>OsStatistics</tt> instance.
     */
    private static OsStatistics instance = null;

    /**
     * Logger.
     */
    private static final Logger logger = Logger.getLogger(OsStatistics.class);

    /**
     * Converts bytes to MB.
     * @param bytes the number of bytes
     * @return the number of MB
     */
    private static int convertBytesToMB(long bytes)
    {
        return  (int) (bytes / 1000000L);
    }

    /**
     * Returns the <tt>OsStatistics</tt> instance.
     * @return the <tt>OsStatistics</tt> instance.
     */
    public static OsStatistics getOsStatistics()
    {
        if (instance == null)
            instance = new OsStatistics();
        return instance;
    }

    /**
     * The <tt>CPUInfo</tt> instance which is used to call the Sigar API and
     * retrieve the CPU usage.
     */
    private CPUInfo cpuInfo;

    /**
     * The method that will return the size of the free memory.
     */
    private Method freeMemoryMethod = null;

    /**
     * The <tt>OperatingSystemMXBean</tt> instance that is used to retrieve
     * memory statistics.
     */
    private final OperatingSystemMXBean operatingSystemMXBean;

    /**
     * Total physical memory size in MB.
     */
    private Integer totalMemory = null;

    /**
     * Constructs <tt>OsStatistics</tt> object.
     */
    private OsStatistics()
    {
        operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        cpuInfo = new CPUInfo();
    }

    /**
     * Returns the CPU usage as double between 0 and 1
     * @return the CPU usage
     */
    public double getCPUUsage()
    {
        if(cpuInfo == null)
            return -1.0;

        try
        {
            return cpuInfo.getCPUUsage();
        }
        catch(Throwable e)
        {
            if(e instanceof UnsatisfiedLinkError)
                cpuInfo = null;
            logger.error("Failed to retrieve the cpu usage.", e);
        }

        return -1.0;
    }

    /**
     * Returns the total physical memory size in MB.
     * @return the total physical memory size in MB.
     */
    public int getTotalMemory()
    {
        if(totalMemory == null)
        {
            Method method;
            try
            {
                method = operatingSystemMXBean.getClass().getMethod(
                    "getTotalPhysicalMemorySize");
            }
            catch (Exception e)
            {
                logger.error("The statistics of the size of the total memory is "
                    + "not available.");
                return -1;
            }
            method.setAccessible(true);
            Long totalMemoryBytes = 0L;
            try
            {
                totalMemoryBytes = (Long) method.invoke(operatingSystemMXBean);
            }
            catch (Exception e)
            {
                logger.error("The statistics of the size of the total memory is "
                    + "not available.");
                return -1;
            }
            totalMemory = convertBytesToMB(totalMemoryBytes);
        }
        return totalMemory;
    }

    /**
     * Returns the size of used physical memory in MB.
     * @return the size of used physical memory in MB.
     */
    public int getUsedMemory()
    {
        if(totalMemory == null)
            return -1;

        if(freeMemoryMethod == null)
        {
            try
            {
                freeMemoryMethod = operatingSystemMXBean.getClass().getMethod(
                    "getFreePhysicalMemorySize");
            }
            catch (Exception e)
            {
                logger.error("The statistics of the size of the used memory is "
                    + "not available.");
                return -1;
            }
            freeMemoryMethod.setAccessible(true);
        }

        long memoryInBytes;
        int memoryInMB = -1;
        try
        {
            memoryInBytes = (Long) freeMemoryMethod.invoke (operatingSystemMXBean);
            memoryInMB = totalMemory - convertBytesToMB(memoryInBytes);
        }
        catch (Exception e)
        {
            logger.error("The statistics of the size of the used memory is "
                + "not available.");
        }
        return memoryInMB;
    }

    /**
     * Implements the <tt>SigarCommandBase</tt> abstract class which is used for
     * retrieving CPU usage information.
     */
    private static class CPUInfo extends SigarCommandBase
    {
        /**
         * Returns the CPU usage information.
         * @return the CPU usage information.
         * @throws SigarException if fails.
         */
        public double getCPUUsage() throws SigarException
        {
            return sigar.getCpuPerc().getCombined();
        }

        @Override
        public void output(String[] arg0) throws SigarException
        {
        }
    }
}

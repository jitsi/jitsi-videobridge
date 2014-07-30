/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.lang.management.*;
import java.lang.reflect.*;

import org.hyperic.sigar.*;
import org.hyperic.sigar.cmd.*;
import org.jitsi.util.*;

/**
 * Implements retrieving statistics from OS( total physical memory size,
 * CPU usage and etc.)
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
     * Total physical memory size in MB.
     */
    private Integer totalMemory = null;

    /**
     * The <tt>OperatingSystemMXBean</tt> instance that is used to retrieve
     * memory statistics.
     */
    private OperatingSystemMXBean operatingSystemMXBean = null;

    /**
     * The method that will return the size of the free memory.
     */
    private Method freeMemoryMethod = null;

    /**
     * Logger.
     */
    private static Logger logger = Logger.getLogger(OsStatistics.class);
    /**
     * The <tt>CPUInfo</tt> instance which is used to call the Sigar API and
     * retrieve the CPU usage.
     */
    private CPUInfo cpuInfo = null;

    /**
     * Constructs <tt>OsStatistics</tt> object.
     */
    private OsStatistics()
    {
        operatingSystemMXBean
            = ManagementFactory.getOperatingSystemMXBean();

        cpuInfo = new CPUInfo();
    }

    /**
     * Returns the <tt>OsStatistics</tt> instance.
     * @return the <tt>OsStatistics</tt> instance.
     */
    public static OsStatistics getOsStatistics()
    {
        if(instance == null)
            instance = new OsStatistics();
        return instance;
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
     * Converts bytes to MB.
     * @param bytes the number of bytes
     * @return the number of MB
     */
    private static int convertBytesToMB(long bytes)
    {
        return  (int) (bytes/1000000L);
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
     * Implements the <tt>SigarCommandBase</tt> abstract class which is used for
     * retrieving CPU usage information.
     */
    private class CPUInfo extends SigarCommandBase
    {

        @Override
        public void output(String[] arg0) throws SigarException
        {

        }

        /**
         * Returns the CPU usage information.
         * @return the CPU usage information.
         * @throws SigarException if fails.
         */
        public double getCPUUsage() throws SigarException
        {
            CpuPerc cpus =
                this.sigar.getCpuPerc();

            return cpus.getCombined();
        }

    }
}

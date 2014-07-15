/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.videobridge.stats.transport.*;

/**
 * A class that manages the statistics. Periodically calls the
 * <tt>StatsGenerator</tt> of the statistics to receive the statistics and then
 * passes them to <tt>StatsTransport</tt> instances that sends them.
 *
 * @author Hristo Terezov
 */
public class StatsManager
{
    /**
     * Map with the <tt>StatsGenerator</tt> instances that the manager will use
     * and the <tt>Statistics</tt> instance that will be updated by the
     * generator.
     */
    private Map<StatsGenerator, Statistics> statistics
        = new HashMap<StatsGenerator, Statistics>();

    /**
     * Map with currently active <tt>Tasks</tt> for
     * <tt>StatsGenerator</tt> instance.
     */
    private Map<StatsGenerator, List<Task>> currentTasks
        = new HashMap<StatsGenerator, List<Task>>();

    /**
     * Adds new statistics task.
     * @param generator the generator of the statistics.
     * @param stat the statistics instance that will store the statistics.
     */
    public void addStat(StatsGenerator generator, Statistics stat)
    {
        statistics.put(generator, stat);
    }

    /**
     * Removes statistics task.
     * @param generator the generator associated with the task.
     */
    public void removeStat(StatsGenerator generator)
    {
        statistics.remove(generator);
        stop(generator);
    }

    /**
     * Removes all statistics tasks.
     */
    public void removeAllStats()
    {
        for(StatsGenerator generator : statistics.keySet())
            removeStat(generator);
    }

    /**
     * Starts statistics task.
     * @param transport the <tt>StatsTransport</tt> that will be used.
     * @param generator the generator for the task.
     * @param period the repeat period in milliseconds.
     */
    public void start(final StatsTransport transport,
        final StatsGenerator generator, final long period)
    {
        List<Task> taskList = currentTasks.get(generator);
        if(taskList == null)
        {
            taskList = new LinkedList<StatsManager.Task>();
            currentTasks.put(generator, taskList);
        }
        Task task = new Task(transport, generator, period);
        taskList.add(task);
        task.start();
    }

    /**
     * Stops statistics task.
     * @param generator the generator associated with the task.
     */
    public void stop(StatsGenerator generator)
    {
        for(Task task : currentTasks.get(generator))
        {
            task.stop();
        }
        currentTasks.remove(generator);
    }

    /**
     * Stops all statistics tasks.
     */
    public void stop()
    {
        for(StatsGenerator generator : currentTasks.keySet())
        {
            stop(generator);
        }
    }

    /**
     * A statistic task.
     */
    private class Task
    {
        /**
         * The transport for the task.
         */
        private final StatsTransport transport;

        /**
         * The generator for the statistics
         */
        private final StatsGenerator generator;

        /**
         * The period for sending statistics.
         */
        private final long period;

        /**
         * The timer.
         */
        private Timer timer;

        /**
         * Listener for the transport.
         */
        private StatsTtransportListener listener = new StatsTtransportListener()
        {
            @Override
            public void onStatsTransportEvent(StatsTransportEvent event)
            {
                switch (event.getType())
                {
                    case INIT_SUCCESS:
                        if(timer != null)
                            break;
                        timer = new Timer();
                        timer.schedule(new TimerTask()
                        {

                            @Override
                            public void run()
                            {
                                generator.generateStatistics(
                                    statistics.get(generator));
                                transport.publishStatistics(
                                    statistics.get(generator));
                            }
                        }, 0, period);
                        break;
                    case INIT_FAIL:
                        Logger.getLogger(StatsManager.class).error(
                            "The initialization of the stats transport failed.");
                        transport.removeStatsTransportListener(this);
                        removeStat(generator);
                        break;
                    case PUBLISH_FAIL:
                        Logger.getLogger(StatsManager.class).error(
                            "The publish of the stats failed.");
                        StatsManager.this.stop(generator);
                        transport.removeStatsTransportListener(this);
                        break;
                    default:
                        break;
                }
            }
        };

        public Task(StatsTransport transport, StatsGenerator generator,
            long period)
        {
            this.transport = transport;
            this.generator = generator;
            this.period = period;
            transport.addStatsTransportListener(listener);
        }

        /**
         * Starts the task
         */
        public void start()
        {
            transport.init();
        }

        /**
         * Stops the task.
         */
        public void stop()
        {
            transport.removeStatsTransportListener(listener);
            if(timer != null)
            {
                timer.cancel();
                timer = null;
            }
        }
    }
}

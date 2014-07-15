/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

/**
 * Defines interface for classes that will generate statistics.
 *
 * @author Hristo Terezov
 */
public interface StatsGenerator
{
    /**
     * Generate statistics and updates the values of the given
     * <tt>Statistics</tt> instance.
     * @param stats <tt>Statistics</tt> object that will be updated with the
     * generated statistics.
     */
    void generateStatistics(Statistics stats);
}
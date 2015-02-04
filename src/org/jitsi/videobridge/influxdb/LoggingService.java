package org.jitsi.videobridge.influxdb;

/**
 * Allows logging of {@link org.jitsi.videobridge.influxdb.Event}s.
 *
 * @author Boris Grozev
 */
public interface LoggingService
{
    public void logEvent(Event event);
}

package org.jitsi.videobridge.log;

/**
 * Allows logging of {@link org.jitsi.videobridge.log.Event}s.
 *
 * @author Boris Grozev
 */
public interface LoggingService
{
    public void logEvent(Event event);
}

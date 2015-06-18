/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.ratecontrol;

/**
 * @author George Politis
 */
public interface BitrateAdaptor
{
    boolean touch();

    boolean increase();

    boolean decrease();
}

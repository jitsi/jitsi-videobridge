/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

/**
 * Implements <tt>WebRtcDataStreamListener</tt> in order to facilitate
 * implementers through extending and overriding methods of interest.
 *
 * @author Lyubomir Marinov
 */
public class WebRtcDataStreamAdapter
    implements WebRtcDataStreamListener
{
    /**
     * {@inheritDoc}
     */
    @Override
    public void onChannelOpened(SctpConnection source, WebRtcDataStream channel)
    {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSctpConnectionReady(SctpConnection source) {}
}

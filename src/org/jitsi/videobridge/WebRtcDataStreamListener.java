/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

/**
 * Interface used to notify about WebRTC data channels opened by
 * remote peer.
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
 */
public interface WebRtcDataStreamListener
{
    /**
     * Fired when new WebRTC data channel is opened.
     *
     * @param source the <tt>SctpConnection</tt> which is the source of the
     * event
     * @param channel the <tt>WebRtcDataStream</tt> that represents opened
     * WebRTC data channel.
     */
    public void onChannelOpened(
            SctpConnection source,
            WebRtcDataStream channel);

    /**
     * Indicates that a <tt>SctpConnection</tt> has established SCTP connection.
     * After that it can be used to either open WebRTC data channel or listen
     * for channels opened by remote peer.
     *
     * @param source the <tt>SctpConnection</tt> which is the source of the
     * event i.e. which has established an SCTP connection
     */
    public void onSctpConnectionReady(SctpConnection source);
}

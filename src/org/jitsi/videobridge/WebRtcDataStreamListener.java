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
 */
public interface WebRtcDataStreamListener
{
    /**
     * Indicates that a <tt>SctpConnection</tt> has established SCTP connection.
     * After that it can be used to either open WebRTC data channel or listen
     * for channels opened by remote peer.
     */
    public void onSctpConnectionReady();

    /**
     * Fired when new WebRTC data channel is opened.
     *
     * @param newStream the <tt>WebRtcDataStream</tt> that represents opened
     * WebRTC data channel.
     */
    public void onChannelOpened(WebRtcDataStream newStream);
}

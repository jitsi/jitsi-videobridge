/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

/**
 * Implements an <tt>RtpChannel</tt> with <tt>MediaType.AUDIO</tt>.
 *
 * @author Lyubomir Marinov
 */
public class AudioChannel
    extends RtpChannel
{
    /**
     * Initializes a new <tt>AudioChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public AudioChannel(Content content, String id)
        throws Exception
    {
        super(content, id);
    }
}

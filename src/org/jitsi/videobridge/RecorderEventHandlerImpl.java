/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.recording.*;

/**
 * An implementation of <tt>RecorderEventHandler</tt> which intercepts
 * <tt>SPEAKER_CHANGED</tt> events and updates their 'ssrc' fields (which
 * contain the SSRC of a video stream) before delegating to another underlying
 * <tt>RecorderEventHandler</tt>. The value to use for an event's 'ssrc' field
 * is a value found to be associated with the audio SSRC of the event (the
 * 'audioSsrc' field).
 *
 * @author Boris Grozev
 */
class RecorderEventHandlerImpl
    implements RecorderEventHandler
{
    /**
     * The <tt>Conference</tt> which has initialized and is employing this
     * instance.
     */
    private final Conference conference;

    private final RecorderEventHandler handler;

    RecorderEventHandlerImpl(
            Conference conference,
            RecorderEventHandler handler)
        throws IllegalArgumentException
    {
        if (conference == null)
            throw new NullPointerException("conference");
        if (handler == null)
            throw new NullPointerException("handler");

        this.conference = conference;
        this.handler = handler;
    }

    @Override
    public void close()
    {
        handler.close();
    }

    /**
     * Notifies this instance that the dominant speaker in the conference
     * has changed.
     * @param endpoint the <tt>Endpoint</tt> corresponding to the new
     * dominant speaker.
     */
    void dominantSpeakerChanged(Endpoint endpoint)
    {
        long ssrc = -1;

        // find the first "video" SSRC for the new dominant endpoint
        for (Channel c : endpoint.getChannels(MediaType.VIDEO))
        {
            int[] ssrcs = ((RtpChannel) c).getReceiveSSRCs();

            if (ssrcs != null && ssrcs.length > 0)
            {
                ssrc = ssrcs[0] & 0xffffffffL;
                break;
            }
        }
        if (ssrc != -1)
        {
            RecorderEvent event = new RecorderEvent();

            event.setType(RecorderEvent.Type.SPEAKER_CHANGED);
            event.setMediaType(MediaType.VIDEO);
            event.setSsrc(ssrc);
            event.setEndpointId(endpoint.getID());
            event.setInstant(System.currentTimeMillis());
            handleEvent(event);
        }
    }

    @Override
    public boolean handleEvent(RecorderEvent event)
    {
        if (event.getEndpointId() == null)
        {
            long ssrc = event.getSsrc();
            Endpoint endpoint
                = conference.findEndpointByReceiveSSRC(ssrc, MediaType.AUDIO);

            if (endpoint == null)
            {
                endpoint
                    = conference.findEndpointByReceiveSSRC(
                            ssrc,
                            MediaType.VIDEO);
            }
            if (endpoint != null)
                event.setEndpointId(endpoint.getID());
        }
        return handler.handleEvent(event);
    }
}

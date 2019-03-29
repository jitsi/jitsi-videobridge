/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import org.jitsi.service.neomedia.recording.*;
import org.jitsi.utils.*;

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
    void dominantSpeakerChanged(AbstractEndpoint endpoint)
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
            AbstractEndpoint endpoint
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

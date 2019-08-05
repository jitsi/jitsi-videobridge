/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.videobridge.util;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.jingle.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Utilities to deserialize {@link PayloadTypePacketExtension} into a
 * {PayloadType}. This is currently in {@code jitsi-videbridge} in order to
 * avoid adding the XML extensions as a dependency to
 * {@code jitsi-media-transform}.
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
public class PayloadTypeUtil
{
    /**
     * Creates a {@link PayloadType} for the payload type described in the
     * given {@link PayloadTypePacketExtension}.
     * @param ext the XML extension which describes the payload type.
     */
    public static PayloadType create(
            @NotNull PayloadTypePacketExtension ext,
            MediaType mediaType)
    {
        Map<String, String> parameters = new ConcurrentHashMap<>();
        for (ParameterPacketExtension parameter : ext.getParameters())
        {
            parameters.put(parameter.getName(), parameter.getValue());
        }

        Set<String> rtcpFeedbackSet = new CopyOnWriteArraySet<>();
        for (RtcpFbPacketExtension rtcpExtension : ext.getRtcpFeedbackTypeList())
        {
            String feedbackType = rtcpExtension.getFeedbackType();
            String feedbackSubtype = rtcpExtension.getFeedbackSubtype();
            if (feedbackSubtype != null && !feedbackSubtype.equals(""))
            {
                feedbackType += " " + feedbackSubtype;
            }

            rtcpFeedbackSet.add(feedbackType);
        }

        byte id = (byte)ext.getID();
        PayloadTypeEncoding encoding
                = PayloadTypeEncoding.Companion.createFrom(ext.getName());
        int clockRate = ext.getClockrate();

        if (PayloadTypeEncoding.VP8 == encoding)
        {
            return new Vp8PayloadType(id, parameters, rtcpFeedbackSet);
        }
        else if (PayloadTypeEncoding.H264 == encoding)
        {
            return new H264PayloadType(id, parameters, rtcpFeedbackSet);
        }
        else if (PayloadTypeEncoding.VP9 == encoding)
        {
            return new Vp9PayloadType(id, parameters, rtcpFeedbackSet);
        }
        else if (PayloadTypeEncoding.RTX == encoding)
        {
            return new RtxPayloadType(id, parameters);
        }
        else if (PayloadTypeEncoding.OPUS == encoding)
        {
            return new OpusPayloadType(id, parameters);
        }
        else if (MediaType.AUDIO.equals(mediaType))
        {
            return new OtherAudioPayloadType(id, clockRate, parameters);
        }
        else if (MediaType.VIDEO.equals(mediaType))
        {
            return new OtherVideoPayloadType(id, clockRate, parameters);
        }

        return null;
    }
}

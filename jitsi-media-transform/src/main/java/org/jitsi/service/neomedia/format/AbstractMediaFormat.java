/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.service.neomedia.format;

import org.jitsi.service.neomedia.*;

import java.util.*;

/**
 * @author bbaldino
 */
public class AbstractMediaFormat implements MediaFormat
{
    protected MediaType mediaType;
    protected String encoding;
    protected double clockRate;
    protected Map<String, String> fmtps = new HashMap<>();
    protected Map<String, String> advancedAttrs = new HashMap<>();
    protected byte rtpPayloadType;
    protected Map<String, String> additionalCodecSettings = new HashMap<>();

    @Override
    public MediaType getMediaType()
    {
        return mediaType;
    }

    @Override
    public String getEncoding()
    {
        return encoding;
    }

    @Override
    public double getClockRate()
    {
        return clockRate;
    }

    @Override
    public String getClockRateString() {
        return null;
    }

    @Override
    public String getRealUsedClockRateString()
    {
        return null;
    }

    @Override
    public boolean equals(Object obj)
    {
        return super.equals(obj);
    }

    @Override
    public boolean formatParametersMatch(Map<String, String> otherFmtps)
    {
        return fmtps.equals(otherFmtps);
    }

    @Override
    public Map<String, String> getAdvancedAttributes()
    {
        return advancedAttrs;
    }

    @Override
    public Map<String, String> getFormatParameters()
    {
        return fmtps;
    }

    @Override
    public byte getRTPPayloadType()
    {
        return rtpPayloadType;
    }

    @Override
    public void setAdditionalCodecSettings(Map<String, String> additionalCodecSettings)
    {
        this.additionalCodecSettings = additionalCodecSettings;
    }

    @Override
    public Map<String, String> getAdditionalCodecSettings()
    {
        return additionalCodecSettings;
    }

    @Override
    public String toString()
    {
        return "";
    }

    @Override
    public boolean matches(MediaFormat format)
    {
        return false;
    }

    @Override
    public boolean matches(MediaType mediaType, String encoding, double clockRate, int channels, Map<String, String> formatParameters)
    {
        return false;
    }
}

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
package org.jitsi_modified.service.neomedia.format;

import org.jitsi.service.neomedia.MediaType;
import org.jitsi.service.neomedia.codec.Constants;

import java.util.HashMap;
import java.util.Map;

/**
 * @author bbaldino
 */
public class RtxMediaFormat extends AbstractMediaFormat
{
    private Map<String, String> fmtp = new HashMap<>();
    public RtxMediaFormat()
    {
        fmtp.put("apt", "100");
    }
    @Override
    public MediaType getMediaType()
    {
        return MediaType.VIDEO;
    }

    @Override
    public String getEncoding()
    {
        return Constants.RTX;
    }

    @Override
    public Map<String, String> getFormatParameters()
    {
        return fmtp;
    }
}


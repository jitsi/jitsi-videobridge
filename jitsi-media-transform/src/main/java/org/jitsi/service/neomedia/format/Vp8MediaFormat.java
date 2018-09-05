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
import org.jitsi.service.neomedia.codec.*;

/**
 * @author bb
 */
public class Vp8MediaFormat extends AbstractMediaFormat
{
    @Override
    public MediaType getMediaType()
    {
        return MediaType.VIDEO;
    }

    @Override
    public String getEncoding()
    {
        return Constants.VP8;
    }
}

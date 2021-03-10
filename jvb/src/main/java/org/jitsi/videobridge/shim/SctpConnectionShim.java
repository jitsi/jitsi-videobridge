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
package org.jitsi.videobridge.shim;

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;

/**
 * Represents a Colibri {@code sctp-connection}
 *
 * @author Brian Baldino
 */
public class SctpConnectionShim extends ChannelShim
{
    /**
     * Initializes a new {@link SctpConnectionShim}.
     * @param id the ID of the {@code sctp-connection} element.
     * @param endpoint the endpoint.
     * @param contentShim the content to which it belongs.
     */
    public SctpConnectionShim(
            @NotNull String id,
            @NotNull EndpointK endpoint,
            ContentShim contentShim,
            Logger parentLogger)
    {
        super(id, endpoint, 1, contentShim, parentLogger);
    }
}

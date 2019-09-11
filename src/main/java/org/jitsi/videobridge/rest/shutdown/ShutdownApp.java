/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.rest.shutdown;

import org.glassfish.jersey.server.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.util.*;

public class ShutdownApp extends ResourceConfig
{
    protected Logger logger = new LoggerImpl(ShutdownApp.class.getName());

    public ShutdownApp(VideobridgeProvider videobridgeProvider)
    {
        logger.info("Graceful shutdown over REST is enabled");
        register(new Shutdown(videobridgeProvider));
    }
}

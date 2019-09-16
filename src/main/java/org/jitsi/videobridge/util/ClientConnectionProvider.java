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

package org.jitsi.videobridge.util;

import org.jitsi.videobridge.xmpp.*;
import org.osgi.framework.*;

/**
 * A class to acquire a {@link ClientConnectionImpl} from a {@link BundleContext}.
 *
 * This abstraction makes it easier to test methods which rely on a
 * {@link ClientConnectionImpl} instance as this class can easily provide
 * a mock instead of the real Videobridge.
 */
public class ClientConnectionProvider extends OsgiServiceProvider<ClientConnectionImpl>
{
    public ClientConnectionProvider(BundleContext bundleContext)
    {
        super(bundleContext, ClientConnectionImpl.class);
    }
}

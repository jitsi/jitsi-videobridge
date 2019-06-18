/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.version;

import org.jitsi.version.*;

/**
 * Extends {@link AbstractVersionActivator} in order to provider the
 * {@code VersionService} implementation for the Jitsi Videobridge.
 *
 * @author Pawel Domas
 */
public class VersionActivator
    extends AbstractVersionActivator
{
    /**
     * The {@code CurrentVersionImpl} instance describing the current version
     * of Jitsi Videobridge.
     */
    private static final CurrentVersionImpl CURRENT_VERSION
        = new CurrentVersionImpl();

    /**
     * {@inheritDoc}
     */
    @Override
    protected CurrentVersion getCurrentVersion()
    {
        return CURRENT_VERSION;
    }
}

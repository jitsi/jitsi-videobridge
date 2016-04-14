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
package org.jitsi.videobridge.eventadmin.influxdb;

import org.jitsi.influxdb.*;
import org.jitsi.service.configuration.*;

/**
 * InfluxDb bundle activator for JVB implementation.
 *
 * @author Pawel Domas
 */
public class Activator
    extends AbstractActivator
{
    /**
     * Creates and returns an implementation of <tt>LoggingHandler</tt>.
     */
    @Override
    protected AbstractLoggingHandler createHandler(ConfigurationService cfg)
        throws Exception
    {
        return new LoggingHandler(cfg);
    }
}

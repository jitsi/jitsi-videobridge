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
package org.jitsi.videobridge.osgi;

import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.utils.logging.*;
import org.osgi.framework.*;

/**
 * Registers LibJitsi's configuration service.
 *
 * @author Boris Grozev
 */
public class ConfigurationActivator
        implements BundleActivator
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
            = Logger.getLogger(ConfigurationActivator.class);

    @Override
    public void start(BundleContext bundleContext)
    {
        ConfigurationService cfg = LibJitsi.getConfigurationService();
        if (cfg != null)
        {
            bundleContext.registerService(
                    ConfigurationService.class.getName(),
                    cfg,
                    null);
            logger.info("Registered the LibJitsi ConfigurationService in OSGi.");
        }
        else
        {
            logger.warn("Failed to registed the Configuration service.");
        }
    }

    @Override
    public void stop(BundleContext bundleContext)
    {
    }
}

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

package org.jitsi.videobridge;

import com.typesafe.config.*;
import org.jitsi.cmd.*;
import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.config.*;

import java.util.*;
import java.util.concurrent.*;

public class VideobridgeConfig
{
    /**
     * The name of the property which specifies the interval in seconds at which
     * a {@link VideobridgeExpireThread} instance should run.
     */
    public static class ExpireThreadIntervalProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.EXPIRE_CHECK_SLEEP_SEC";
        protected static final String propName = "videobridge.expire-thread-interval";

        protected ExpireThreadIntervalProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> (int)config.getDuration(propName, TimeUnit.SECONDS))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static ExpireThreadIntervalProperty expireThreadInterval = new ExpireThreadIntervalProperty();

    public static class EnabledApisProperty extends AbstractConfigProperty<List<String>>
    {
        protected static final String commandLineArgName = "--apis";
        protected static final String legacyXmppApiPropName = "org.jitsi.videobridge.xmpp";
        protected static final String legacyRestApiPropName = "org.jitsi.videobridge.rest";
        protected static final String propName = "videobridge.enabled-apis";

        protected EnabledApisProperty()
        {
            super(new JvbPropertyConfig<List<String>>()
                // For backwards compatibility, we'll check if the APIs are passed as command-line
                // arguments
                .fromCommandLine(EnabledApisProperty::commandLineSupplier)
                .fromLegacyConfig(EnabledApisProperty::legacySupplier)
                .fromNewConfig(config -> config.getStringList(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }

        protected static List<String> commandLineSupplier(CmdLine cmdLine)
        {
            String apis = cmdLine.getOptionValue(commandLineArgName, null);
            if (apis == null)
            {
                throw new ConfigPropertyNotFoundException(commandLineArgName);
            }
            else
            {
                return Arrays.asList(apis.split(","));
            }
        }

        protected static List<String> legacySupplier(Config config)
        {
            throw new ConfigPropertyNotFoundException(legacyRestApiPropName + ", " + legacyXmppApiPropName);
            //NOTE(brian): we can't reliably detect the APIs being defined in a legacy config
            // file, because the XMPP key used is 'org.jitsi.videobridge.xmpp', but we also
            // have other values in that path (e.g. 'org.jitsi.videobridge.xmpp.user') and
            // typesafe.config merges those paths, blowing away the simple 'org.jitsi.videobridge.xmpp'
            // value and, if we check for config.hasPath('org.jitsi.videobridge.xmpp') we'll get
            // a result of 'true' if any property path contains that path (like
            // 'org.jitsi.videobridge.xmpp.user=XXX' mentioned above).  Will ignore checking the
            // legacy config file for these for now.


            /* See comment above ^
            // The XMPP and REST APIs are modeled as 2 discrete properties in
            // the legacy config
            List<String> enabledApis = new ArrayList<>();
            if (config.hasPath(legacyXmppApiPropName))
            {
                enabledApis.add(Videobridge.XMPP_API);
            }
            if (config.hasPath(legacyRestApiPropName))
            {
                enabledApis.add(Videobridge.REST_API);
            }
            if (enabledApis.isEmpty())
            {
                throw new ConfigPropertyNotFoundException(legacyRestApiPropName + ", " + legacyXmppApiPropName);
            }
            return enabledApis;
            */
        }

        public static boolean isEnabled(String apiName)
        {
            return enabledApis.get().stream().anyMatch(api -> api.equalsIgnoreCase(apiName));
        }
    }

    public static EnabledApisProperty enabledApis = new EnabledApisProperty();
}

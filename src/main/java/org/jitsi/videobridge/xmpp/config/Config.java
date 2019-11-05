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

package org.jitsi.videobridge.xmpp.config;

import com.typesafe.config.*;
import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.config.*;
import org.jitsi.xmpp.mucclient.*;

import java.util.*;
import java.util.stream.*;

public class Config
{
    public static class XmppClientConfig
    {
        protected static class EnabledProperty extends AbstractConfigProperty<Boolean>
        {
            protected static final String propName = "videobridge.apis.xmpp-client.enabled";

            private static EnabledProperty singleton = new EnabledProperty();

            public EnabledProperty()
            {
                super(new JvbPropertyConfig<Boolean>()
                    .fromNewConfig(config -> config.getBoolean(propName))
                    //TODO: these configs may be candidates to re-read each time, but we
                    // need code to detect/handle changes
                    .readOnce()
                    .throwIfNotFound()
                );
            }

            public static Boolean getValue()
            {
                return singleton.get();
            }
        }

        public static boolean isEnabled()
        {
            return EnabledProperty.getValue();
        }

        /**
         * A list of XMPP client connection configurations which describe which XMPP
         * servers and MUCs the bridge should join.  These connections serve as an API
         * to control the bridge.
         */
        //TODO: this prop reads all sub-fields of the given names, which throws off
        // the validation...could we mark it as 'recursive' or something?
        protected static class XmppClientConfigsProperty extends AbstractConfigProperty<List<MucClientConfiguration>>
        {
            protected static final String legacyPropName = "org.jitsi.videobridge.xmpp.user";
            protected static final String propName = "videobridge.apis.xmpp-client.configs";

            private static final XmppClientConfigsProperty singleton = new XmppClientConfigsProperty();

            public XmppClientConfigsProperty()
            {
                super(new JvbPropertyConfig<List<MucClientConfiguration>>()
//                    .fromLegacyConfig(config -> {
                        //TODO

//                    })
                    .fromNewConfig(config -> config.getObject(propName).entrySet().stream()
                        .map(e -> fromConfig(e.getKey(), (ConfigObject)e.getValue()))
                        .collect(Collectors.toList()))
                    //TODO: these configs may be candidates to re-read each time, but we
                    // need code to detect/handle changes
                    .readOnce()
                    .throwIfNotFound()
                );
            }

            /**
             * From a configuration ID and a Map of property names and values, create
             * a {@link MucClientConfiguration}
             * @param id the id of the {@link MucClientConfiguration}
             * @param configData the data for the client configuration
             * @return the created {@link MucClientConfiguration}
             */
            private static MucClientConfiguration fromConfig(String id, ConfigObject configData)
            {
                MucClientConfiguration config = new MucClientConfiguration(id);
                configData.forEach((propName, propValue) ->
                    config.setProperty(propName, propValue.unwrapped().toString()));
                return config;
            }

            public static List<MucClientConfiguration> getValue()
            {
                return singleton.get();
            }
        }

        public static List<MucClientConfiguration> getClientConfigs()
        {
            return XmppClientConfigsProperty.getValue();
        }
    }
}

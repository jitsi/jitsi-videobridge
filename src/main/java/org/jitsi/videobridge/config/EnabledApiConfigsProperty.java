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

package org.jitsi.videobridge.config;

import com.typesafe.config.*;
import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.util.config.*;

import java.util.*;
import java.util.stream.*;

/**
 * Builds a list of {@link ApiConfig} objects, each of which holds the configuration
 * for an enabled JVB API.
 *
 * There's a lot of logic here because what we're doing is a bit complex: the old
 * way of enabling APIs and the new way differs quite a bit, so we need to coerce
 * each way into a common result.
 *
 * The old method involved passing a "--apis=xx,xx" command-line argument to list
 * all of the enabled APIs.  If 'xmpp' was amongst those enabled APIs, then other
 * command-line argument were needed to provide other required values.
 *
 * The new method defines a list of enabled-api configs, where each object
 * has the name of the enabled API and any other configuration values needed.
 *
 * This implementation provides fall-through behavior for every individual
 * property, meaning that if someone is using the old config and provides
 * only the {@code --apis=xmpp} argument, then the original defaults will
 * be filled in by the new defaults config file.
 */
public class EnabledApiConfigsProperty extends AbstractConfigProperty<List<ApiConfig>>
{
    protected static final String apisCommandLineArgName = "--apis";
    protected static final String enabledApisPropName = "videobridge.enabled-apis";

    /**
     * Property representing the list of names of the enabled APIs
     */
    protected static class EnabledApiNamesProperty extends AbstractConfigProperty<List<String>>
    {
        protected EnabledApiNamesProperty()
        {
            super(new JvbPropertyConfig<List<String>>()
                .fromCommandLine(apisCommandLineArgName, (cmdLine) ->
                    Arrays.stream(cmdLine.getOptionValue(apisCommandLineArgName, "").split(","))
                        .filter(s -> !s.isEmpty())
                        .collect(new EmptyListToNullCollector<>())
                )
                .fromNewConfig(config -> config.getConfigList(enabledApisPropName)
                    .stream()
                    .map(apiConfig -> apiConfig.getString("name"))
                    .collect(new EmptyListToNullCollector<>())
                )
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    protected static EnabledApiNamesProperty enableApiNames = new EnabledApiNamesProperty();

    protected EnabledApiConfigsProperty()
    {
        // The logic for determining the list of enabled APIs is handled by EnabledApiNamesProperty.
        // Here, we retrieve the value there and iterate over them to create ApiConfig instances
        // for each one
        super(new JvbPropertyConfig<List<ApiConfig>>()
            .suppliedBy(() ->
                enableApiNames.get().stream()
                .map(ApiConfigFactory::create)
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
            )
            .readOnce()
            .throwIfNotFound()
        );

        //TODO: old way which didn't build on/need EnabledApiNamesProperty.  Better?

        // When retrieving from either the command line or the new config, we
        // build a list of the names of the enabled APIs and then call the
        // ApiConfigFactory to create the proper ApiConfig type (including
        // retrieving any other config values which may be needed).
        // Note that we use a custom collector to return 'null' in case
        // the list is empty, as an empty list is equivalent to
        // the property not being found
        //super(new JvbPropertyConfig<List<ApiConfig>>()
        //    .fromCommandLine(apisCommandLineArgName, (cmdLine) ->
        //        Arrays.stream(cmdLine.getOptionValue(apisCommandLineArgName, "").split(","))
        //        .map(ApiConfigFactory::create)
        //        .filter(Objects::nonNull)
        //        .collect(new EmptyListToNullCollector<>()))
        //    .fromNewConfig(config ->
        //        config.getConfigList(enabledApisPropName)
        //        .stream()
        //        .map(apiConfig -> apiConfig.getString("name"))
        //        .map(ApiConfigFactory::create)
        //        .filter(Objects::nonNull)
        //        .collect(new EmptyListToNullCollector<>()))
        //    .readOnce()
        //    .throwIfNotFound()
        //);
    }

    /**
     * Create {@link ApiConfig} instances from a given API name.  Accesses
     * other properties if needed to build an {@link ApiConfig}.
     */
    protected static class ApiConfigFactory
    {
        public static ApiConfig create(String apiName)
        {
            if ("xmpp".equalsIgnoreCase(apiName))
            {
                String host = XmppApiConfig.host.get();
                int port = Integer.parseInt(XmppApiConfig.port.get());
                String domain = XmppApiConfig.domain.get();
                String subdomain = XmppApiConfig.subdomain.get();
                String secret = XmppApiConfig.secret.get();
                return new XmppComponentApiConfig(host, port, domain, subdomain, secret);
            }
            else if ("rest".equalsIgnoreCase(apiName))
            {
                return new RestApiConfig();
            }
            return null;
        }
    }

    public boolean isApiEnabled(String apiName)
    {
        return enableApiNames.get().stream().anyMatch(enabledApiName -> enabledApiName.equalsIgnoreCase(apiName));
    }

    public ApiConfig getConfigForApi(String apiName)
    {
        return get().stream().filter(config -> config.apiName().equalsIgnoreCase(apiName)).findFirst().orElse(null);
    }

    /**
     * Additional configuration properties required to create an {@link XmppApiConfig}
     */
    protected static class XmppApiConfig
    {
        /**
         * The IP address or the name of the XMPP host to connect to.
         */
        protected static class HostProperty extends AbstractConfigProperty<String>
        {
            protected static final String legacyXmppHostArgName = "--host";

            protected HostProperty()
            {
                super(new JvbPropertyConfig<String>()
                    .fromCommandLine(legacyXmppHostArgName)
                    .fromNewConfig(config -> {
                        Config xmppApiConfig = findNewApiConfig(config, "xmpp");

                        if (xmppApiConfig == null)
                        {
                            throw new ConfigPropertyNotFoundException(enabledApisPropName + " xmpp");
                        }

                        return xmppApiConfig.getString("host");
                    })
                    .readOnce()
                    .returnNullIfNotFound()
                );
            }
        }

        public static HostProperty host = new HostProperty();

        /**
         * The XMPP domain to use.
         */
        protected static class DomainProperty extends AbstractConfigProperty<String>
        {
            protected static final String legacyXmppDomainArgName = "--domain";

            protected DomainProperty()
            {
                super(new JvbPropertyConfig<String>()
                    .fromCommandLine(legacyXmppDomainArgName)
                    .fromNewConfig(config -> {
                        Config xmppApiConfig = findNewApiConfig(config, "xmpp");

                        if (xmppApiConfig == null)
                        {
                            throw new ConfigPropertyNotFoundException(enabledApisPropName + " xmpp");
                        }

                        return xmppApiConfig.getString("domain");
                    })
                    .readOnce()
                    .returnNullIfNotFound()
                );
            }
        }

        public static DomainProperty domain = new DomainProperty();

        /**
         * The sub-domain name for the videobridge component.
         */
        protected static class SubdomainProperty extends AbstractConfigProperty<String>
        {
            protected static final String legacyXmppSubdomainArgName = "--subdomain";

            protected SubdomainProperty()
            {
                super(new JvbPropertyConfig<String>()
                    .fromCommandLine(legacyXmppSubdomainArgName)
                    .fromNewConfig(config -> {
                        Config xmppApiConfig = findNewApiConfig(config, "xmpp");

                        if (xmppApiConfig == null)
                        {
                            throw new ConfigPropertyNotFoundException(enabledApisPropName + " xmpp");
                        }

                        return xmppApiConfig.getString("subdomain");
                    })
                    .readOnce()
                    .returnNullIfNotFound()
                );
            }
        }

        public static SubdomainProperty subdomain = new SubdomainProperty();

        /**
         * The secret key for the sub-domain of the Jabber component implemented by this application
         * with which it is to authenticate to the XMPP server to connect to.
         */
        protected static class SecretProperty extends AbstractConfigProperty<String>
        {
            protected static final String legacyXmppSecretArgName = "--secret";

            protected SecretProperty()
            {
                super(new JvbPropertyConfig<String>()
                    .fromCommandLine(legacyXmppSecretArgName)
                    .fromNewConfig(config -> {
                        Config xmppApiConfig = findNewApiConfig(config, "xmpp");

                        if (xmppApiConfig == null)
                        {
                            throw new ConfigPropertyNotFoundException(enabledApisPropName + " xmpp");
                        }

                        return xmppApiConfig.getString("secret");
                    })
                    .readOnce()
                    .returnNullIfNotFound()
                );
            }
        }

        public static SecretProperty secret = new SecretProperty();

        /**
         * The port of the XMPP host to connect on.
         */
        protected static class PortProperty extends AbstractConfigProperty<String>
        {
            protected static final String legacyXmppPortArgName = "--port";

            protected PortProperty()
            {
                super(new JvbPropertyConfig<String>()
                    .fromCommandLine(legacyXmppPortArgName)
                    .fromNewConfig(config -> {
                        // Walk the list of API configs to find the XMPP config, then grab its domain property
                        Config xmppApiConfig = findNewApiConfig(config, "xmpp");

                        if (xmppApiConfig == null)
                        {
                            throw new ConfigPropertyNotFoundException(enabledApisPropName + " xmpp");
                        }

                        return xmppApiConfig.getString("port");
                    })
                    .readOnce()
                    .returnNullIfNotFound()
                );
            }
        }

        public static PortProperty port = new PortProperty();

        /**
         * Given the top-level config and an API name, return the {@link Config} object
         * which contains the configuration information for the API, or null if it
         * doesn't exist
         *
         * @param config
         * @param apiName
         * @return
         */
        private static Config findNewApiConfig(Config config, String apiName)
        {
            return config.getConfigList(EnabledApiConfigsProperty.enabledApisPropName)
                .stream()
                .filter(c -> c.getString("name").equalsIgnoreCase(apiName))
                .findFirst()
                .orElse(null);
        }
    }
}

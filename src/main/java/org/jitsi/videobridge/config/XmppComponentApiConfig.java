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

import org.jitsi.videobridge.*;

public class XmppComponentApiConfig implements ApiConfig
{
    protected final String host;
    protected final int port;
    protected final String domain;
    protected final String subdomain;
    protected final String secret;

    public XmppComponentApiConfig(String host, int port, String domain, String subdomain, String secret)
    {
        this.host = host;
        this.port = port;
        this.domain = domain;
        this.subdomain = subdomain;
        this.secret = secret;
    }

    @Override
    public String apiName()
    {
        return Videobridge.XMPP_API;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public String getDomain()
    {
        return domain;
    }

    public String getSubdomain()
    {
        return subdomain;
    }

    public String getSecret()
    {
        return secret;
    }

}

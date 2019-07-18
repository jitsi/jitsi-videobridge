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
package org.jitsi.videobridge.rest;

import java.net.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.proxy.*;

/**
 * Fixes defects of Jetty's {@code ProxyServlet}.
 *
 * @author Lyubomir Marinov
 */
public class ProxyServletImpl
    extends ProxyServlet.Transparent
{
    /**
     * The &quot;proxyTo&quot; {@code ServletConfig} init parameter required by
     * Jetty's transparent {@code ProxyServlet}.
     */
    private String proxyTo;

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(ServletConfig config)
        throws ServletException
    {
        super.init(config);

        // Jetty's transparent ProxyServlet may introduce a / at the end of the
        // request path. Such behavior is incorrect.
        String proxyTo = config.getInitParameter("proxyTo");

        if (proxyTo != null && !proxyTo.endsWith("/"))
            this.proxyTo = proxyTo;
    }

    /**
     * {@inheritDoc}
     *
     * If Jetty's transparent {@code ProxyServlet} introduces a / at the end of
     * the request path, removes it (because such behavior is incorrect).
     */
    @Override
    protected String rewriteTarget(HttpServletRequest request)
    {
        String rewrittenURIStr = super.rewriteTarget(request);

        if (proxyTo != null && rewrittenURIStr != null)
        {
            String requestPath = request.getRequestURI();

            if (requestPath != null && !requestPath.endsWith("/"))
            {
                URI rewrittenURI
                    = URI.create(rewrittenURIStr).normalize();
                String rewrittenPath = rewrittenURI.getPath();
                int len;

                if (rewrittenPath != null
                        && (len = rewrittenPath.length()) > 1
                        && rewrittenPath.endsWith("/"))
                {
                    rewrittenPath = rewrittenPath.substring(0, len - 1);

                    try
                    {
                        rewrittenURI
                            = new URI(
                                    rewrittenURI.getScheme(),
                                    rewrittenURI.getAuthority(),
                                    rewrittenPath,
                                    rewrittenURI.getQuery(),
                                    rewrittenURI.getFragment());
                    }
                    catch (URISyntaxException urise)
                    {
                    }
                }
                rewrittenURIStr = rewrittenURI.toString();
            }
        }
        return rewrittenURIStr;
    }
}

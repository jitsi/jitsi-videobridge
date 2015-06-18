/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
    protected URI rewriteURI(HttpServletRequest request)
    {
        URI rewrittenURI = super.rewriteURI(request);

        if (proxyTo != null)
        {
            String requestPath = request.getRequestURI();

            if (requestPath != null && !requestPath.endsWith("/"))
            {
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
            }
        }
        return rewrittenURI;
    }
}

/*
 * Copyright @ 2019 8x8, Inc
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

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;

/**
 * Filters all TRACE requests returning 405 Not Allowed.
 *
 * @author Damian Minkov
 */
public class TraceFilter
    implements Filter
{
    @Override
    public void init(FilterConfig filterConfig)
        throws ServletException
    {}

    @Override
    public void doFilter(
        ServletRequest servletRequest,
        ServletResponse servletResponse,
        FilterChain filterChain)
        throws IOException, ServletException
    {
        HttpServletRequest httpRequest
            = (HttpServletRequest) servletRequest;
        HttpServletResponse httpResponse
            = (HttpServletResponse) servletResponse;

        if ("TRACE".equalsIgnoreCase(httpRequest.getMethod())) {
            httpResponse.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy()
    {}
}

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

import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;

/**
 * Implements a {@code Servlet} which enables long polling with asynchronous
 * HTTP request handling.
 *
 * @author Lyubomir Marinov
 */
class LongPollingServlet
    extends HttpServlet
{
    /**
     * {@inheritDoc}
     */
    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        // TODO Auto-generated method stub
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
}

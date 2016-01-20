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
package org.jitsi.videobridge.rest;

import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;

import org.json.simple.*;
import org.osgi.framework.*;

import org.jitsi.videobridge.*;
import org.jitsi.osgi.*;
import org.eclipse.jetty.continuation.*;


//TEMP
import java.util.concurrent.*;
import java.util.*;
import java.text.SimpleDateFormat;

/**
 * Implements a {@code Servlet} which enables long polling with asynchronous
 * HTTP request handling.
 *
 * @author Lyubomir Marinov
 */
class LongPollingServlet
    extends HttpServlet
{

    BlockingQueue<JSONObject> queue = new LinkedBlockingQueue();
    BundleContext bundleContext;

    public LongPollingServlet(BundleContext bundleContext)
    {
      this.bundleContext = bundleContext;
    }
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
      System.out.println("BB: GOT long polling in thread " + Thread.currentThread().getId());
      // We don't get the target here (TODO: is that something that could
      //  be fixed?), so we need to do a bit more work to extract the bits of the path
      //  we're interested in.
      // The stuff we're interested in starts after the /colibri/
      String colibriPath = request.getRequestURL().toString().split("colibri/")[1];
      String target = colibriPath.split("/")[0];
      if (target.equals("subscribeToEvents"))
      {
        Videobridge videobridge = getVideobridge();
        String confId = colibriPath.split("/")[1];
        System.out.println("BB: subscribing to conference events for conference " + confId);
        Conference conference = videobridge.getConference(confId, null);
        if (conference == null)
        {
          System.out.println("Couldn't find conference " + confId);
          response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
        else
        {
          System.out.println("found conf object");
          System.out.println("Waiting for event");
          AsyncContext context = request.startAsync();
          new Thread(new ConfPushAsyncService(context, conference)).run();
        }
      }
    }

    /**
     * Gets the {@code Videobridge} instance available to this Jetty
     * {@code Handler}.
     *
     * @return the {@code Videobridge} instance available to this Jetty
     * {@code Handler} or {@code null} if no {@code Videobridge} instance is
     * available to this Jetty {@code Handler}
     */
    public Videobridge getVideobridge()
    {
        return ServiceUtils2.getService(this.bundleContext, Videobridge.class);
    }
}

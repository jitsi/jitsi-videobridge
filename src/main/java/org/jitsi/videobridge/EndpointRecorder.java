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
package org.jitsi.videobridge;

import java.io.*;
import java.util.*;

import org.jitsi.utils.logging.*;
import org.json.simple.*;

/**
 * A class responsible for saving to disk information about <tt>Endpoint</tt>s.
 *
 * @author Boris Grozev
 *
 * @deprecated remove-with-recording
 */
@Deprecated
public class EndpointRecorder
{
    /**
     * The <tt>Logger</tt> used by the <tt>EndpointRecorder</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(EndpointRecorder.class);

    /**
     * The <tt>File</tt> to which this <tt>EndpointRecorder</tt> will write.
     */
    private final File file;

    /**
     * Whether this <tt>EndpointRecorder</tt> is closed.
     */
    private boolean closed;

    /**
     * Map between an endpoint ID and the <tt>EndpointInfo</tt> structure
     * containing information about it.
     */
    private final Map<String, EndpointInfo> endpoints = new HashMap<>();

    /**
     * Tries to initialize an <tt>EndpointRecorder</tt> which is to write
     * to <tt>filename</tt>.
     * @param filename the name of the file to write to.
     * @throws IOException
     */
    public EndpointRecorder(String filename)
        throws IOException
    {
        file = new File(filename);
        if (!file.createNewFile())
            throw new IOException("File exists or cannot be created: " + file);
        if (!file.canWrite())
            throw new IOException("Cannot write to file: " + file);

        closed = false;
    }

    /**
     * Updates this <tt>EndpointRecorder</tt> with information from a specific
     * <tt>Endpoint</tt>
     * @param endpoint the <tt>Endpoint</tt> to add.
     */
    public void updateEndpoint(AbstractEndpoint endpoint)
    {
        String id = endpoint.getID();

        synchronized(endpoints)
        {
            EndpointInfo endpointInfo = endpoints.get(id);

            if (endpointInfo == null)
            {
                endpointInfo = new EndpointInfo(endpoint);
                endpoints.put(id, endpointInfo);
            }
            else
            {
                endpointInfo.displayName = endpoint.getDisplayName();
            }
        }

        writeEndpoints();
    }

    /**
     * Closes this <tt>EndpointRecorder</tt>.
     */
    public void close()
    {
        writeEndpoints();

        closed = true;
    }

    /**
     * Writes all information to the file.
     */
    private void writeEndpoints()
    {
        if (closed)
            return;

        try
        {
            FileWriter writer = new FileWriter(file, false);
            writer.write("[\n");

            synchronized (endpoints)
            {
                int size = endpoints.size();
                int idx = 0;
                for (EndpointInfo endpointInfo : endpoints.values())
                {
                    writer.write("    ");
                    writer.write(endpointInfo.getJSON());
                    if (++idx != size)
                        writer.write(",");
                    writer.write("\n");
                }
            }

            writer.write("]\n");
            writer.close();
        }
        catch (IOException ioe)
        {
            logger.warn("Failed to write endpoints: " + ioe);
        }
    }

    /**
     * Represents the information saved for each <tt>Endpoint</tt>.
     */
    private static class EndpointInfo
    {
        /**
         * ID.
         */
        private final String id;

        /**
         * Display name.
         */
        String displayName;

        /**
         * Initializes a new <tt>EndpointInfo</tt> instance with information
         * from a given <tt>Endpoint</tt>.
         * @param endpoint the endpoint to use to initialize a new
         * <tt>EndpointInfo</tt> instance.
         */
        private EndpointInfo(AbstractEndpoint endpoint)
        {
            id = endpoint.getID();
            displayName = endpoint.getDisplayName();
        }

        /**
         * Returns a string representing this <tt>EndpointInfo</tt> in JSON
         * format.
         * @return a string representing this <tt>EndpointInfo</tt> in JSON
         * format.
         */
        private String getJSON()
        {
            return
                "{\"id\":\"" + JSONValue.escape(id) + "\",\"displayName\":\""
                    + JSONValue.escape(displayName) + "\"}";
        }
    }
}

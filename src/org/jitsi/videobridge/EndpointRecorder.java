/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import org.jitsi.util.*;

import java.io.*;
import java.util.*;

/**
 * A class responsible for saving to disk information about <tt>Endpoint</tt>s.
 *
 * @author Boris Grozev
 */
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
    private File file;

    /**
     * Whether this <tt>EndpointRecorder</tt> is closed.
     */
    private boolean closed;

    /**
     * Map between an endpoint ID and the <tt>EndpointInfo</tt> structure
     * containing information about it.
     */
    private final Map<String, EndpointInfo> endpoints
            = new HashMap<String, EndpointInfo>();

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
    public void updateEndpoint(Endpoint endpoint)
    {
        String id = endpoint.getID();
        EndpointInfo endpointInfo = endpoints.get(id);

        if (endpointInfo == null)
        {
            endpointInfo = new EndpointInfo(endpoint);
        }
        else
        {
            endpointInfo.displayName = endpoint.getDisplayName();
        }

        endpoints.put(id, endpointInfo);

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

            int size = endpoints.size();
            int idx = 0;
            for (EndpointInfo endpointInfo : endpoints.values())
            {
                if (++idx == size)
                    writer.write("    " + endpointInfo.getJSON() + "\n");
                else
                    writer.write("    " + endpointInfo.getJSON() + ",\n");
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
        private String id;

        /**
         * Display name.
         */
        private String displayName;

        /**
         * Initializes a new <tt>EndpointInfo</tt> instance with information
         * from a given <tt>Endpoint</tt>.
         * @param endpoint the endpoint to use to initialize a new
         * <tt>EndpointInfo</tt> instance.
         */
        private EndpointInfo(Endpoint endpoint)
        {
            this.id = endpoint.getID();
            this.displayName = endpoint.getDisplayName();
        }

        /**
         * Returns a string representing this <tt>EndpointInfo</tt> in JSON
         * format.
         * @return a string representing this <tt>EndpointInfo</tt> in JSON
         * format.
         */
        private String getJSON()
        {
            return "{\"id\" : \"" + id + "\", \"displayName\" : \""
                    + displayName + "\" }";
        }
    }
}

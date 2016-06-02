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
package org.jitsi.videobridge.rest.ssi;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.util.resource.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.Scanner;

/**
 * ResourceHandler implementation which check a property to search
 * for preconfigured paths to be scanned for ssi tags. Rest of the resources
 * are loaded by default implementation of ResourceHandler.
 *
 * TODO:
 * Current implementation doesn't respect file modifications, if you
 * modify the included files and the main one hasn't change a response
 * HttpStatus.NOT_MODIFIED_304 will be returned.
 *
 * @author Damian Minkov
 */
public class SSIResourceHandler
    extends ResourceHandler
{
    /**
     * Prefix that can configure multiple location aliases.
     * rest.api.jetty.SSIResourceHandler.paths=/;/somefolder/somepage.html
     */
    private static final String JETTY_SSI_RESOURCE_HANDLER_PATHS
        = Videobridge.REST_API_PNAME + ".jetty.SSIResourceHandler.paths";

    /**
     * Start of ssi command.
     */
    private static final String SSI_CMD_START = "<!--#";

    /**
     * End ssi command.
     */
    private static final String SSI_CMD_END = "-->";

    /**
     * SSI command include.
     */
    private static final String SSI_CMD_INCLUDE = "include";

    /**
     * Parameter of SSI command.
     */
    private static final String SSI_PARAM_VIRTUAL = "virtual";

    /**
     * Parameter of SSI command.
     */
    private static final String SSI_PARAM_FILE = "file";

    /**
     * The {@code ConfigurationService} which looks up values of configuration
     * properties.
     */
    protected ConfigurationService cfg;

    /**
     * The list of targets which will be processed by this ResourceHandler
     * otherwise defaults will be used.
     */
    private final List<String> ssiPaths;

    /**
     * Constructs new SSIResourceHandler.
     * @param cfg the configuration.
     */
    public SSIResourceHandler(ConfigurationService cfg)
    {
        this.cfg = cfg;

        String paths = ConfigUtils.getString(
            cfg, JETTY_SSI_RESOURCE_HANDLER_PATHS, null);

        if(paths != null)
        {
            ssiPaths = Arrays.asList(paths.split(";"));
        }
        else
            ssiPaths = new ArrayList<>();
    }

    /**
     * Overrides default handler entry.
     * @param target the target location
     * @param baseRequest the base request
     * @param request the request
     * @param response the response
     * @throws IOException
     * @throws ServletException
     */
    @Override
    public void handle(
                    String target,
                    Request baseRequest,
                    HttpServletRequest request,
                    HttpServletResponse response)
        throws IOException,
               ServletException
    {
        if(ssiPaths.contains(target))
            this.handleSSIRequest(target, baseRequest, request, response);
        else
            super.handle(target, baseRequest, request, response);
    }

    /**
     * Process the request to serve a file while checking it for
     * ssi replacement tags
     * @param target the target location
     * @param baseRequest the base request
     * @param request the request
     * @param response the response
     * @throws IOException
     * @throws ServletException
     */
    private void handleSSIRequest(
                        String target,
                        Request baseRequest,
                        HttpServletRequest request,
                        HttpServletResponse response)
        throws IOException,
               ServletException
    {
        HttpServletResponseWrapper servletResponseWrapper
            = new HttpServletResponseWrapper(response);
        super.handle(target, baseRequest, request, servletResponseWrapper);

        byte[] processedResult = processContentForServerSideIncludes(
            servletResponseWrapper.getContent());
        // if content length had changed, update it
        response.setContentLength(processedResult.length);

        response.getOutputStream().write(processedResult);
    }

    /**
     * Process the current content and searches for ssi tags to replace them
     * with the content.
     * Currently only include virtual is supported.
     * @param content the content to scan for ssi tags.
     * @return the resulting content.
     * @throws IOException
     */
    private byte[] processContentForServerSideIncludes(
            byte[] content)
        throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if(content == null)
            return out.toByteArray();

        // scanner delimiter is the same as BufferedReader.readLine
        // "A line is considered to be terminated by any one of a line feed
        // ('\n'), a carriage return ('\r'), or a carriage return followed
        // immediately by a linefeed."
        Scanner scanner = new Scanner(
            new ByteArrayInputStream(content), "UTF-8")
                .useDelimiter("(?<=\n)|(?!\n)(?<=\r)");
        while(scanner.hasNext())
        {
            String line = scanner.next();

            if (line.contains(SSI_CMD_START))
            {
                int startIx = line.indexOf(SSI_CMD_START);
                int endIx = line.indexOf(SSI_CMD_END, startIx);

                if(endIx != -1)
                {
                    // include virtual="config.js"
                    String cmd = line.substring(
                        startIx + SSI_CMD_START.length(),
                        endIx);

                    // write everything to this point
                    out.write(line.substring(0, startIx)
                        .getBytes(StandardCharsets.UTF_8));

                    if(!processSSICmd(cmd, out))
                    {
                        // print the original text
                        out.write(
                            SSI_CMD_START.getBytes(StandardCharsets.UTF_8));
                        out.write(cmd.getBytes(StandardCharsets.UTF_8));
                        out.write(
                            SSI_CMD_END.getBytes(StandardCharsets.UTF_8));
                    }

                    // write everything after the ssi directive
                    out.write(
                        line.substring(endIx + SSI_CMD_END.length(),
                            line.length())
                            .getBytes(StandardCharsets.UTF_8));

                    // stop processing
                    continue;
                }
            }

            // by default write the line if nothing is found
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }

        return out.toByteArray();
    }

    /**
     * Processing the ssi commands. Currently only include is supported.
     * @param cmd command with parameters.
     * @param out the result
     * @return return true if some processing had been done, false otherwise
     * @throws IOException
     */
    private boolean processSSICmd(String cmd, OutputStream out)
        throws IOException
    {
        // include command
        if(cmd.startsWith(SSI_CMD_INCLUDE) && cmd.contains("="))
        {
            String parameterName = cmd.substring(
                SSI_CMD_INCLUDE.length(), cmd.indexOf("=")).trim();

            // we need virtual or file parameter
            if(!parameterName.equals(SSI_PARAM_VIRTUAL)
                && !parameterName.equals(SSI_PARAM_FILE))
                return false;

            String fileToInclude
                = cmd.substring(cmd.indexOf("=") + 1).trim();

            // remove surrounding " if any
            fileToInclude = fileToInclude.replaceAll("\\\"", "");

            // if file is virtual(its a location) we can have an alias for that
            // and we need to check that
            if(parameterName.equals(SSI_PARAM_VIRTUAL))
            {
                // add / in the beginning to represent a relative address
                if(!fileToInclude.startsWith("/"))
                    fileToInclude = "/" + fileToInclude;

                // alias check
                String aliasValue
                    = ConfigUtils.getString(cfg,
                    RESTBundleActivator.JETTY_RESOURCE_HANDLER_ALIAS_PREFIX
                        + "." + fileToInclude,
                    null);
                if(aliasValue != null)
                    fileToInclude = aliasValue;
            }

            Resource r = Resource.newResource(fileToInclude);

            if(r.exists())
            {
                r.writeTo(out, 0, r.length());
                return true;
            }
        }
        else
        {
            // other commands not supported yet
        }

        return false;
    }
}

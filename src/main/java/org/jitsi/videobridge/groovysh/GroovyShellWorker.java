/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.groovysh;

import groovy.lang.*;
import org.codehaus.groovy.tools.shell.*;
import org.jitsi.util.*;

import java.io.*;
import java.net.*;

/**
 * @author George Politis
 */
public class GroovyShellWorker
        implements Runnable
{
    /**
     * The <tt>Logger</tt> used by the <tt>GroovyShellThread</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(GroovyShellWorker.class);
    /**
     *
     */
    private final Socket clientSocket;

    /**
     *
     */
    private Binding binding;

    /**
     * Ctor.
     *
     * @param clientSocket
     * @param binding
     */
    public GroovyShellWorker(Socket clientSocket, Binding binding) {
        this.clientSocket = clientSocket;
        this.binding = binding;
    }

    /**
     *
     * @return
     */
    public Socket getClientSocket()
    {
        return clientSocket;
    }

    /**
     *
     */
    @Override
    public void run() {

        if (clientSocket == null)
        {
            return;
        }

        PrintStream out = null;
        InputStream in = null;

        try
        {
            out = new PrintStream(clientSocket.getOutputStream());
            in = clientSocket.getInputStream();

            if (binding == null)
            {
                binding = new Binding();
            }

            binding.setVariable("out", out);

            final IO gio = new IO(in, out, out);
            final Groovysh groovysh = new Groovysh(binding, gio);

            groovysh.run("");
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
        }
        finally
        {
            if (out != null)
            {
                out.close();
            }

            if (in != null)
            {
                try
                {
                    in.close();
                }
                catch (IOException e)
                {
                    logger.error("Failed to close the input stream.", e);
                }
            }

            if (clientSocket != null)
            {
                try
                {
                    clientSocket.close();
                }
                catch (IOException e)
                {
                    logger.error("Failed to close the client socket.", e);
                }
            }
        }
    }
}

/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.groovysh;

import groovy.lang.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author George Politis
 */
public class GroovyShellActivator
        implements BundleActivator
{
    /**
     * The <tt>Logger</tt> used by the <tt>GroovyShellActivator</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(GroovyShellActivator.class);

    /**
     * Accept no more than 10 simultaneous connections at a time.
     */
    private static final int MAX_CLIENTS = 10;

    /**
     * Some easy to remember prime number.
     */
    private static final int SERVER_SOCKET_PORT = 6869;

    /**
     * Wait no more than 3 minutes for user input.
     */
    private static final int CLIENT_SOCKET_TIMEOUT = 3 * 60 * 1000;

    /**
     * Executor service for client connection handlers.
     */
    private static final ExecutorService executorService
            = Executors.newFixedThreadPool(MAX_CLIENTS);

    /**
     *
     */
    private ServerSocket serverSocket;


    @Override
    public void start(final BundleContext ctx) {
        // Create the server thread.
        Thread serverThread = new Thread() {

            @Override
            public void run()
            {
                try
                {
                    serverSocket = new ServerSocket(SERVER_SOCKET_PORT);

                    while (true)
                    {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setSoTimeout(CLIENT_SOCKET_TIMEOUT);

                        // Set the variables to expose to the shell.
                        Binding binding = new Binding();
                        binding.setVariable("state", new GroovyShellState(ctx));

                        // Create and start shell thread.
                        GroovyShellWorker w
                                = new GroovyShellWorker(clientSocket, binding);

                        executorService.execute(w);
                    }
                }
                catch (IOException e)
                {
                    logger.error(e.getMessage(), e);
                }
                finally
                {
                    GroovyShellActivator.this.stop(ctx);
                }

            }
        };

        serverThread.setDaemon(true);
        serverThread.start();
    }

    @Override
    public void stop(BundleContext context) {

        // Close the server socket.
        try
        {
            serverSocket.close();
        }
        catch (IOException e)
        {
            logger.error(e.getMessage(), e);
        }

        // Close the client sockets.
        List<Runnable> dropped = executorService.shutdownNow();
        if (dropped != null && !dropped.isEmpty())
        {
            for (Runnable runnable : dropped)
            {
                if (runnable instanceof GroovyShellWorker)
                {
                    GroovyShellWorker w = (GroovyShellWorker) runnable;
                    try
                    {
                        w.getClientSocket().close();
                    }
                    catch (IOException e)
                    {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        }
    }
}

package org.jitsi.videobridge.openfire;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Adds the SLF4JBridgeHandler in an OSGi bundle.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class SLF4JBridgeHandlerBundleActivator implements BundleActivator
{
    @Override
    public void start( BundleContext context ) throws Exception
    {
        // Remove existing handlers attached to j.u.l root logger
        SLF4JBridgeHandler.removeHandlersForRootLogger();

        SLF4JBridgeHandler.install();
    }

    @Override
    public void stop( BundleContext context ) throws Exception
    {
        SLF4JBridgeHandler.uninstall();
    }
}

/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.stats;

import org.osgi.framework.*;

/**
 *
 * @author Lyubomir Marinov
 */
class BundleContextHolder2
{
    /**
     * The OSGi <tt>BundleContext</tt> in which this <tt>StartsTransport</tt>
     * has been started and has not been stopped yet.
     */
    private BundleContext bundleContext;

    protected void bundleContextChanged(
            BundleContext oldValue,
            BundleContext newValue)
    {
    }

    /**
     * Gets the OSGi <tt>BundleContext</tt> in which this
     * <tt>StartsTransport</tt> has been started and has not been stopped yet.
     *
     * @return the OSGi <tt>BundleContext</tt> in which this
     * <tt>StartsTransport</tt> has been started and has not been stopped yet
     */
    protected synchronized BundleContext getBundleContext()
    {
        return bundleContext;
    }

    void start(BundleContext bundleContext)
        throws Exception
    {
        BundleContext oldValue = null, newValue = null;

        synchronized (this)
        {
            if (this.bundleContext != bundleContext)
            {
                oldValue = this.bundleContext;
                this.bundleContext = bundleContext;
                newValue = this.bundleContext;
            }
        }
        if (oldValue != newValue)
            bundleContextChanged(oldValue, newValue);
    }

    synchronized void stop(BundleContext bundleContext)
        throws Exception
    {
        BundleContext oldValue = null, newValue = null;

        synchronized (this)
        {
            if (this.bundleContext == bundleContext)
            {
                oldValue = this.bundleContext;
                this.bundleContext = null;
                newValue = this.bundleContext;
            }
        }
        if (oldValue != newValue)
            bundleContextChanged(oldValue, newValue);
    }
}

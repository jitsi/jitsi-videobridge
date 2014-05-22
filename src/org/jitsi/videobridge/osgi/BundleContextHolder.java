/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.osgi;

import org.osgi.framework.*;

/**
 * Represents an accessor to a <tt>BundleContext</tt>. When the accessor
 * acquires a <tt>BundleContext</tt> or looses it, it notifies the
 * <tt>BundleActivator</tt>s registered with it.
 *
 * @author Lyubomir Marinov
 */
interface BundleContextHolder
{
    /**
     * Registers a <tt>BundleActivator</tt> with this instance to be notified
     * when this instance acquires a <tt>BundleContext</tt> or looses it.
     * 
     * @param bundleActivator the <tt>BundlerActivator</tt> to register with
     * this instance
     */
    public void addBundleActivator(BundleActivator bundleActivator);

    /**
     * Gets the <tt>BundleContext</tt> in which this instance has been started.
     *
     * @return the <tt>BundleContext</tt> in which this instance has been
     * started or <tt>null</tt> if this instance has not been started in a
     * <tt>BundleContext</tt>
     */
    public BundleContext getBundleContext();

    /**
     * Unregisters a <tt>BundleActivator</tt> with this instance to no longer be
     * notified when this instance acquires a <tt>BundleContext</tt> or looses
     * it.
     * 
     * @param bundleActivator the <tt>BundlerActivator</tt> to unregister with
     * this instance
     */
    public void removeBundleActivator(BundleActivator bundleActivator);
}

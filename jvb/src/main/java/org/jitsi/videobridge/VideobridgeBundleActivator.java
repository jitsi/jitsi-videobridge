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
package org.jitsi.videobridge;

import org.osgi.framework.*;

/**
 * Implements a <tt>BundleActivator</tt> for <tt>Videobridge</tt> which starts
 * and stops it in a <tt>BundleContext</tt>.
 * <p>
 * <b>Warning</b>: The class <tt>BundleActivatorImpl</tt> is to be considered
 * internal, its access modifier is public in order to allow the OSGi framework
 * to find it by name and instantiate it.
 * </p>
 *
 * @author Lyubomir Marinov
 */
public class VideobridgeBundleActivator
    implements BundleActivator
{
    /**
     * The <tt>ServiceRegistration</tt> of a <tt>Videobridge</tt> instance
     * within the <tt>BundleContext</tt> in which this instance has been
     * started.
     */
    private ServiceRegistration<Videobridge> serviceRegistration;

    /**
     * Starts the <tt>Videobridge</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Initializes and starts a new <tt>Videobridge</tt> instance and registers
     * it as an OSGi service in the specified <tt>bundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the
     * <tt>Videobridge</tt> OSGi bundle is to start
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        // We still need to 'start' the Videobridge instance via OSGi because
        // Videobridge requires a BundleContext for various things, so we have
        // the instance initialization done in VideobridgeSupplier so that as
        // we port services away from OSGi they can still get an instance
        // of Videobridge.  Eventually, once everything is moved over, this
        // activator can go away and all 'services' can just take the Videobridge
        // in their constructor
        Videobridge videobridge = VideobridgeSupplierKt.singleton().get();

        videobridge.start();

        ServiceRegistration<Videobridge> serviceRegistration = null;

        try
        {
            serviceRegistration
                = bundleContext.registerService(
                        Videobridge.class,
                        videobridge,
                        null);
        }
        finally
        {
            if (serviceRegistration == null)
                videobridge.stop();
            else
                this.serviceRegistration = serviceRegistration;
        }
    }

    /**
     * Stops the <tt>Videobridge</tt> OSGi bundle in a <tt>BundleContext</tt>.
     * Unregisters and stops a <tt>Videobridge</tt> instance in the specified
     * <tt>BundleContext</tt> if such an instance has been registered and
     * started.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which the
     * <tt>Videobridge</tt> OSGi bundle is to stop
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        ServiceRegistration<Videobridge> serviceRegistration
            = this.serviceRegistration;

        this.serviceRegistration = null;

        Videobridge videobridge = null;

        try
        {
            videobridge
                = bundleContext.getService(serviceRegistration.getReference());
        }
        finally
        {
            serviceRegistration.unregister();
            if (videobridge != null)
                videobridge.stop();
        }
    }
}

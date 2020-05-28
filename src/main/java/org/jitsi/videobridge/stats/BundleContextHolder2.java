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
package org.jitsi.videobridge.stats;

import org.osgi.framework.*;

/**
 * Represents an accessor to a <tt>BundleContext</tt> instance.
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

    /**
     * Notifies this instance that the <tt>BundleContext</tt> it provides
     * access to changed from a specific <tt>oldValue</tt> to a specific
     * <tt>newValue</tt>.
     *
     * @param oldValue the <tt>BundleContext</tt> which this instance provided
     * access to before the change
     * @param newValue the <tt>BundleContext</tt> which this instance provides
     * access to after the change
     */
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

    /**
     * Starts this instance in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which this instance is
     * to start
     * @throws Exception if this instance failed to start in the specified
     * <tt>bundleContext</tt>
     */
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
        {
            bundleContextChanged(oldValue, newValue);
        }
    }

    /**
     * Stops this instance in a specific <tt>BundleContext</tt>.
     *
     * @param bundleContext the <tt>BundleContext</tt> in which this instance is
     * to stop
     * @throws Exception if this instance failed to stop in the specified
     * <tt>bundleContext</tt>
     */
    void stop(BundleContext bundleContext)
        throws Exception
    {
        BundleContext oldValue = null;

        synchronized (this)
        {
            if (this.bundleContext == bundleContext)
            {
                oldValue = this.bundleContext;
                this.bundleContext = null;
            }
        }
        if (oldValue != null)
        {
            bundleContextChanged(oldValue, null);
        }
    }
}

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
package org.jitsi.videobridge.version;

import net.java.sip.communicator.util.*;
import org.jitsi.service.resources.*;
import org.jitsi.service.version.Version;
import org.jitsi.service.version.util.*;

/**
 * A static implementation of the {@link Version} interface.
 *
 * @author George Politis
 */
public class VersionImpl
    extends AbstractVersion
{
    /**
     * The version major of the current Jitsi Videobridge version. In an example
     * 2.3.1 version string 2 is the version major. The version major number
     * changes when a relatively extensive set of new features and possibly
     * rearchitecturing have been applied to the Jitsi.
     */
    public static final int VERSION_MAJOR = 0;

    /**
     * The version major of the current Jitsi Videobridge version. In an example
     * 2.3.1 version string 2 is the version major. The version major number
     * changes when a relatively extensive set of new features and possibly
     * rearchitecturing have been applied to the Jitsi.
     */
    public static final int VERSION_MINOR = 1;

    /**
     * Indicates whether this version represents a prerelease (i.e. an
     * incomplete release like an alpha, beta or release candidate version).
     */
    public static final boolean IS_PRE_RELEASE_VERSION  = false;

    /**
     * Returns the version prerelease ID of the current Jitsi Videobridge
     * version and {@code null} if this version is not a prerelease.
     */
    public static final String PRE_RELEASE_ID = "beta1";

    /**
     * Indicates if this Jitsi Videobridge version corresponds to a nightly
     * build of a repository snapshot or to an official Jitsi Videobridge
     * release.
     */
    public static final boolean IS_NIGHTLY_BUILD = true;

    /**
     * The default name of this application.
     */
    public static final String DEFAULT_APPLICATION_NAME = "JVB";

    /**
     * The name of this application.
     */
    private static String applicationName = null;

    /**
     * Returns the {@code VersionImpl} instance describing the current version
     * of Jitsi Videobridge.
     */
    public static final VersionImpl CURRENT_VERSION = new VersionImpl();

    /**
     * The nightly build ID. This file is auto-updated by build.xml.
     */
    public static final String NIGHTLY_BUILD_ID="build.SVN";

    /**
     * Creates version object with default (current) values.
     */
    private VersionImpl()
    {
        super(VERSION_MAJOR, VERSION_MINOR, NIGHTLY_BUILD_ID);
    }

    /**
     * Creates version object with custom major, minor and nightly build id.
     *
     * @param majorVersion the major version to use.
     * @param minorVersion the minor version to use.
     * @param nightlyBuildID the nightly build id value for new version object.
     */
    private VersionImpl(int majorVersion,
                        int minorVersion,
                        String nightlyBuildID)
    {
        super(majorVersion, minorVersion, nightlyBuildID);
    }

    /**
     * Indicates if this Jitsi Videobridge version corresponds to a nightly
     * build of a repository snapshot or to an official Jitsi Videobridge
     * release.
     *
     * @return {@code true} if this is a build of a nightly repository snapshot
     * and {@code false} if this is an official Jitsi Videobridge release.
     */
    public boolean isNightly()
    {
        return IS_NIGHTLY_BUILD;
    }

    /**
     * Indicates whether this version represents a prerelease (i.e. an
     * incomplete release like an alpha, beta or release candidate version).
     *
     * @return {@code true} if this version represents a prerelease and
     * {@code false} otherwise.
     */
    public boolean isPreRelease()
    {
        return IS_PRE_RELEASE_VERSION;
    }

    /**
     * Returns the version prerelease ID of the current Jitsi Videobridge
     * version and {@code null} if this version is not a prerelease.
     *
     * @return a {@code String} containing the version prerelease ID.
     */
    public String getPreReleaseID()
    {
        return isPreRelease() ? PRE_RELEASE_ID : null;
    }

    /**
     * Returns the {@code VersionImpl} instance describing the current version
     * of Jitsi Videobridge.
     *
     * @return the {@code VersionImpl} instance describing the current version
     * of Jitsi Videobridge.
     */
    public static final VersionImpl currentVersion()
    {
        return CURRENT_VERSION;
    }

    /**
     * Returns the {@code VersionImpl} instance describing the version with the
     * parameters supplied.
     *
     * @param majorVersion the major version to use.
     * @param minorVersion the minor version to use.
     * @param nightlyBuildID the nightly build id value for new version object.
     * @return the {@code VersionImpl} instance describing the version with
     * parameters supplied.
     */
    public static final VersionImpl customVersion(
            int majorVersion,
            int minorVersion,
            String nightlyBuildID)
    {
        return new VersionImpl(majorVersion, minorVersion, nightlyBuildID);
    }

    /**
     * Returns the name of the application that we're currently running. Default
     * MUST be Jitsi Videobridge.
     *
     * @return the name of the application that we're currently running. Default
     * MUST be Jitsi Videobridge.
     */
    public String getApplicationName()
    {
        if (applicationName == null)
        {
            try
            {
                // XXX There is no need to have the ResourceManagementService
                // instance as a static field of the VersionImpl class because
                // it will be used once only anyway.
                ResourceManagementService resources
                    = ServiceUtils.getService(
                            VersionActivator.getBundleContext(),
                            ResourceManagementService.class);

                if (resources != null)
                {
                    applicationName
                        = resources.getSettingsString(
                                "service.gui.APPLICATION_NAME");
                }
            }
            catch (Exception e)
            {
                // If resource bundle is not found or the key is missing, return
                // the default name.
            }
            finally
            {
                if (applicationName == null)
                {
                    // Allow the application name to be overridden by the user.
                    applicationName
                        = System.getProperty(Version.PNAME_APPLICATION_NAME);
                    if (applicationName == null
                            || applicationName.length() == 0)
                    {
                        applicationName = DEFAULT_APPLICATION_NAME;
                    }
                }
            }
        }
        return applicationName;
    }
}

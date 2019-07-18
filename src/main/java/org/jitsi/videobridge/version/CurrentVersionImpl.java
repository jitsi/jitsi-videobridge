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
package org.jitsi.videobridge.version;

import org.jitsi.version.*;

/**
 * A static implementation of the {@link CurrentVersion} interface for Jitsi
 * Videobridge.
 *
 * @author George Politis
 * @author Pawel Domas
 */
public class CurrentVersionImpl
    implements CurrentVersion
{
    /**
     * The version major of the current application version. In an example
     * 2.3.1 version string 2 is the version major. The version major number
     * changes when a relatively extensive set of new features and possibly
     * rearchitecturing have been applied to the Jitsi Videobridge.
     */
    public static final int VERSION_MAJOR = 0;

    /**
     * Returns the version minor of the current application version. In an
     * example 2.3.1 version string 3 is the version minor. The version minor
     * number changes after adding enhancements and possibly new features to a
     * given Jitsi Videobridge version.
     */
    public static final int VERSION_MINOR = 1;

    /**
     * The version prerelease ID of the current application version.
     */
    public static final String PRE_RELEASE_ID = null;// "beta1";

    /**
     * The nightly build ID. This file is auto-updated by build.xml.
     */
    public static final String NIGHTLY_BUILD_ID = "build.SVN";

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMajorVersion()
    {
        return VERSION_MAJOR;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinorVersion()
    {
        return VERSION_MINOR;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getNightlyBuildID()
    {
        return NIGHTLY_BUILD_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPreReleaseID()
    {
        return PRE_RELEASE_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultAppName()
    {
        return "JVB";
    }
}

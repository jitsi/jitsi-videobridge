/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.cc;

import org.jitsi.utils.config.*;
import org.jitsi.videobridge.util.config.*;

import java.util.concurrent.*;

public class CcConfig
{

    /**
     * The property that holds the interval/period in milliseconds
     * at which {@link BandwidthProbing#run()} is to be invoked.
     */
    public static class PaddingPeriodProperty extends AbstractConfigProperty<Long>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.PADDING_PERIOD_MS";
        protected static final String propName = "videobridge.cc.padding-period";

        protected PaddingPeriodProperty()
        {
            super(new JvbPropertyConfig<Long>()
                .fromLegacyConfig(config -> config.getLong(legacyPropName))
                .fromNewConfig(config -> config.getDuration(propName, TimeUnit.SECONDS))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static PaddingPeriodProperty paddingPeriod = new PaddingPeriodProperty();

    /**
     * The property that holds the bandwidth estimation threshold.
     */
    public static class BweChangeThresholdPctProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT";
        protected static final String propName = "videobridge.cc.bwe-change-threshold-pct";

        protected BweChangeThresholdPctProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> config.getInt(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static BweChangeThresholdPctProperty bweChangeThresholdPct = new BweChangeThresholdPctProperty();

    public static class ThumbnailMaxHeightProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT";
        protected static final String propName = "videobridge.cc.thumbnail-max-height-px";

        protected ThumbnailMaxHeightProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> config.getInt(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static ThumbnailMaxHeightProperty thumbnailMaxHeightPx = new ThumbnailMaxHeightProperty();

    /**
     * The property name of the preferred resolution to allocate for the onstage
     * participant, before allocating bandwidth for the thumbnails.
     */
    public static class OnstagePreferredHeightProperty extends AbstractConfigProperty<Integer>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT";
        protected static final String propName = "videobridge.cc.onstage-preferred-height-px";

        protected OnstagePreferredHeightProperty()
        {
            super(new JvbPropertyConfig<Integer>()
                .fromLegacyConfig(config -> config.getInt(legacyPropName))
                .fromNewConfig(config -> config.getInt(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static OnstagePreferredHeightProperty onstagePreferredHeight = new OnstagePreferredHeightProperty();

    /**
     * The property of the preferred frame rate to allocate for the onstage
     * participant.
     */
    public static class OnstagePreferredFramerateProperty extends AbstractConfigProperty<Double>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE";
        protected static final String propName = "videobridge.cc.onstage-preferred-framerate-fps";

        protected OnstagePreferredFramerateProperty()
        {
            super(new JvbPropertyConfig<Double>()
                .fromLegacyConfig(config -> config.getDouble(legacyPropName))
                .fromNewConfig(config -> config.getDouble(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static OnstagePreferredFramerateProperty onstagePreferredFramerate = new OnstagePreferredFramerateProperty();

    /**
     * The property of the option that enables/disables video suspension
     * for the on-stage participant.
     */
    public static class OnstageVideoSuspensionEnabledProperty extends AbstractConfigProperty<Boolean>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND";
        protected static final String propName = "videobridge.cc.onstage-video-suspension-enabled";

        protected OnstageVideoSuspensionEnabledProperty()
        {
            super(new JvbPropertyConfig<Boolean>()
                .fromLegacyConfig(config -> config.getBoolean(legacyPropName))
                .fromNewConfig(config -> config.getBoolean(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static OnstageVideoSuspensionEnabledProperty onstageVideoSuspensionEnabled = new OnstageVideoSuspensionEnabledProperty();

    /**
     * The property used to trust bandwidth estimations.
     */
    public static class TrustBweProperty extends AbstractConfigProperty<Boolean>
    {
        protected static final String legacyPropName = "org.jitsi.videobridge.TRUST_BWE";
        protected static final String propName = "videobridge.cc.trust-bwe";

        protected TrustBweProperty()
        {
            super(new JvbPropertyConfig<Boolean>()
                .fromLegacyConfig(config -> config.getBoolean(legacyPropName))
                .fromNewConfig(config -> config.getBoolean(propName))
                .readOnce()
                .throwIfNotFound()
            );
        }
    }

    public static TrustBweProperty trustBwe = new TrustBweProperty();
}

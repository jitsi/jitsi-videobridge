package org.jitsi.videobridge.openfire;

import org.jitsi.videobridge.osgi.JvbBundleConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * OSGi bundles description for the Openfire Jitsi Videobridge plugin.
 *
 * This implementation takes the bundle that ships with the Jitsi Videobridge plugin, and filters out the bundles that
 * are not applicable to the Openfire plugin.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class JvbOpenfireBundleConfig extends JvbBundleConfig
{
    @Override
    protected String[][] getBundlesImpl()
    {
        final String[][] parentBundles = super.getBundlesImpl();
        final String[][] result = new String[ parentBundles.length ][];
        for ( int i = 0; i < parentBundles.length; i++)
        {
            final List<String> bundles = new ArrayList<>();
            for ( int j = 0; j < parentBundles[i].length; j++ )
            {
                if ( ! "org/jitsi/videobridge/rest/RESTBundleActivator".equals( parentBundles[i][j] ) )
                {
                    bundles.add( parentBundles[ i ][ j ] );
                }
            }
            result[i] = bundles.toArray( new String[0] );
        }
        return result;
    }
}

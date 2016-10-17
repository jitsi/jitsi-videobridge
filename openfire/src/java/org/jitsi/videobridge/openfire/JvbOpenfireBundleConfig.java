package org.jitsi.videobridge.openfire;

import org.jitsi.videobridge.osgi.JvbBundleConfig;

import java.util.*;

/**
 * OSGi bundles description for the Openfire Jitsi Videobridge plugin.
 *
 * This implementation takes the bundle that ships with the Jitsi Videobridge
 * plugin, and filters out the bundles that are not applicable to the Openfire
 * plugin.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class JvbOpenfireBundleConfig extends JvbBundleConfig
{
    @Override
    protected String[][] getBundlesImpl()
    {
        final String[][] parentBundles = super.getBundlesImpl();

        // Convert to a 'list-of-lists', as lists are easier to manipulate.
        final List<List<String>> result = matrixToLists( parentBundles );

        // Very first activator: logging!
        final List<String> logging = new ArrayList<>();
        logging.add(
            "org.jitsi.videobridge.openfire.SLF4JBridgeHandlerBundleActivator");
        logging.add( "org.slf4j.osgi.logservice.impl.Activator" );
        result.add( 0, logging );

        // Remove all activators that we don't want.
        final Iterator<List<String>> iterator = result.iterator();
        while ( iterator.hasNext() )
        {
            final List<String> bundles = iterator.next();
            if (bundles.remove("org/jitsi/videobridge/rest/RESTBundleActivator")
                && bundles.isEmpty() )
            {
                // Delete the bundle if we removed the only activator.
                iterator.remove();
            }
        }

        // Convert back to an 'array-of-arrays' and return.
        return listsToMatrix( result );
    }

    /**
     * Converts an array-of-arrays into a list-of-lists.
     *
     * @param matrix an array-of-arrays.
     * @return A list-of-lists.
     */
    public static List<List<String>> matrixToLists( String[][] matrix )
    {
        final List<List<String>> result = new ArrayList<>();
        for ( final String[] array : matrix )
        {
            // Can't use Arrays.asList(), as the returned list does not
            // implement List#remove()
            final List<String> entries = new ArrayList<>();
            Collections.addAll( entries, array );
            result.add( entries );
        }
        return result;
    }

    /**
     * Converts a list-of-lists into an array-of-arrays.
     *
     * @param lists a list-of-lists.
     * @return an array-of-arrays.
     */
    public static String[][] listsToMatrix( List<List<String>> lists )
    {
        final String[][] result = new String[ lists.size() ][];
        for ( int i = 0; i < lists.size(); i++ )
        {
            final List<String> list = lists.get( i );
            result[ i ] = list.toArray( new String[ list.size() ] );
        }
        return result;
    }
}

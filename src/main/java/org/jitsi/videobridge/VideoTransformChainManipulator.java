package org.jitsi.videobridge;

import org.jitsi.impl.neomedia.transform.TransformEngine;

/**
 * An interface for video transform chain manipulation
 *
 * You can register VideoTransformChainManipulator through OSGi.
 *
 * @author Laszlo Luczo
 * @author Norbert Papp
 */
public interface VideoTransformChainManipulator
{

    /**
     * Manipulate transform engines and returns the new engine chain.
     * Called when {@link RtpChannel} creates a new transform chain.
     *
     * @param transformEngines transform engine chain
     * @return manipulated transform engine chain
     */
    TransformEngine[] manipulate(TransformEngine[] transformEngines);
}

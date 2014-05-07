/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.dtls;

import org.bouncycastle.crypto.tls.*;
import org.jitsi.util.*;

import java.io.*;

/**
 * FIXME: class copied from org.jitsi.impl.neomedia.transform.dtls.TlsClientImpl
 *        and stripped from SRTP code to be used with SctpConnection.
 *        @author Pawel Domas
 *
 * Implements {@link TlsClient} for the purposes of supporting DTLS-SRTP.
 *
 * @author Lyubomir Marinov
 */
public class DataTlsClientImpl
    extends DefaultTlsClient
{
    /**
     * The <tt>Logger</tt> used by the <tt>TlsClientImpl</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(DataTlsClientImpl.class);

    private final TlsAuthentication authentication
        = new TlsAuthenticationImpl();

    private final DtlsLayer dtlsLayer;

    /**
     * Initializes a new <tt>TlsClientImpl</tt> instance.
     *
     */
    public DataTlsClientImpl(DtlsLayer dtlsLayer)
    {
        this.dtlsLayer = dtlsLayer;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized TlsAuthentication getAuthentication()
        throws IOException
    {
        return authentication;
    }

    /**
     * {@inheritDoc}
     *
     * The implementation of <tt>TlsClientImpl</tt> always returns
     * <tt>ProtocolVersion.DTLSv10</tt> because <tt>ProtocolVersion.DTLSv12</tt>
     * does not work with the Bouncy Castle Crypto APIs at the time of this
     * writing.
     */
    @Override
    public ProtocolVersion getClientVersion()
    {
        return ProtocolVersion.DTLSv10;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProtocolVersion getMinimumVersion()
    {
        return ProtocolVersion.DTLSv10;
    }

    /**
     * Implements {@link org.bouncycastle.crypto.tls.TlsAuthentication} for the purposes of supporting
     * DTLS-SRTP.
     *
     * @author Lyubomir Marinov
     */
    private class TlsAuthenticationImpl
        implements TlsAuthentication
    {
        private TlsCredentials clientCredentials;

        /**
         * {@inheritDoc}
         */
        public TlsCredentials getClientCredentials(
                CertificateRequest certificateRequest)
            throws IOException
        {
            if (clientCredentials == null)
            {
                clientCredentials
                    = new DefaultTlsSignerCredentials(
                            context,
                            dtlsLayer.getCertificate(),
                            dtlsLayer.getKeyPair().getPrivate());
            }
            return clientCredentials;
        }

        /**
         * {@inheritDoc}
         */
        public void notifyServerCertificate(Certificate serverCertificate)
            throws IOException
        {
            try
            {
                dtlsLayer.verifyAndValidateCertificate(
                    serverCertificate);
            }
            catch (Exception e)
            {
                logger.error(
                        "Failed to verify and/or validate server certificate!",
                        e);
                if (e instanceof IOException)
                    throw (IOException) e;
                else
                    throw new IOException(e);
            }
        }
    }
}

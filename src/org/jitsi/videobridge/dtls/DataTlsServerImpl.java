/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.dtls;

import org.bouncycastle.crypto.tls.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.io.*;
import java.util.*;

/**
 * FIXME: class copied from org.jitsi.impl.neomedia.transform.dtls.TlsServerImpl
 *        and stripped from SRTP code to be used with SctpConnection.
 *        @author Pawel Domas
 *
 *
 * Implements {@link org.bouncycastle.crypto.tls.TlsServer} for the purposes of supporting DTLS-SRTP.
 *
 * @author Lyubomir Marinov
 */
public class DataTlsServerImpl
    extends DefaultTlsServer
{
    /**
     * The <tt>Logger</tt> used by the <tt>TlsServerImpl</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(DataTlsServerImpl.class);

    private final CertificateRequest certificateRequest
        = new CertificateRequest(
                new short[] { ClientCertificateType.rsa_sign },
                /* supportedSignatureAlgorithms */ null,
                /* certificateAuthorities */ null);

    private final DtlsLayer dtlsLayer;

    private TlsSignerCredentials rsaSignerCredentials;

    /**
     * Initializes a new <tt>TlsServerImpl</tt> instance.
     *
     */
    public DataTlsServerImpl(DtlsLayer dtlsLayer)
    {
        this.dtlsLayer = dtlsLayer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CertificateRequest getCertificateRequest()
    {
        return certificateRequest;
    }

    /**
     * {@inheritDoc}
     *
     * The implementation of <tt>TlsServerImpl</tt> always returns
     * <tt>ProtocolVersion.DTLSv10</tt> because <tt>ProtocolVersion.DTLSv12</tt>
     * does not work with the Bouncy Castle Crypto APIs at the time of this
     * writing.
     */
    @Override
    protected ProtocolVersion getMaximumVersion()
    {
        return ProtocolVersion.DTLSv10;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ProtocolVersion getMinimumVersion()
    {
        return ProtocolVersion.DTLSv10;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected TlsSignerCredentials getRSASignerCredentials()
        throws IOException
    {
        if (rsaSignerCredentials == null)
        {
            rsaSignerCredentials
                = new DefaultTlsSignerCredentials(
                        context,
                        dtlsLayer.getCertificate(),
                        dtlsLayer.getKeyPair().getPrivate());
        }
        return rsaSignerCredentials;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyClientCertificate(Certificate clientCertificate)
        throws IOException
    {
        try
        {
            dtlsLayer.verifyAndValidateCertificate(clientCertificate);
        }
        catch (Exception e)
        {
            logger.error(
                    "Failed to verify and/or validate client certificate!",
                    e);
            if (e instanceof IOException)
                throw (IOException) e;
            else
                throw new IOException(e);
        }
    }
}

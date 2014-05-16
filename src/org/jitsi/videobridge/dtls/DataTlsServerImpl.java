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

    /**
     *
     * @see TlsServer#getCertificateRequest()
     */
    private final CertificateRequest certificateRequest
        = new CertificateRequest(
                new short[] { ClientCertificateType.rsa_sign },
                /* supportedSignatureAlgorithms */ null,
                /* certificateAuthorities */ null);

    private final DtlsLayer dtlsLayer;

    /**
     *
     * @see DefaultTlsServer#getRSAEncryptionCredentials()
     */
    private TlsEncryptionCredentials rsaEncryptionCredentials;

    /**
     *
     * @see DefaultTlsServer#getRSASignerCredentials()
     */
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
     *
     * Overrides the super implementation to explicitly specify cipher suites
     * which we know to be supported by Bouncy Castle. At the time of this
     * writing, we know that Bouncy Castle implements Client Key Exchange only
     * with <tt>TLS_ECDHE_WITH_XXX</tt> and <tt>TLS_RSA_WITH_XXX</tt>.
     */
    @Override
    protected int[] getCipherSuites()
    {
        return
            new int[]
                {
/* core/src/main/java/org/bouncycastle/crypto/tls/DefaultTlsServer.java */
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA256,
                    CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256,
                    CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA
/* core/src/test/java/org/bouncycastle/crypto/tls/test/MockDTLSServer.java */
//                        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
//                        CipherSuite.TLS_ECDHE_RSA_WITH_ESTREAM_SALSA20_SHA1,
//                        CipherSuite.TLS_ECDHE_RSA_WITH_SALSA20_SHA1,
//                        CipherSuite.TLS_RSA_WITH_ESTREAM_SALSA20_SHA1,
//                        CipherSuite.TLS_RSA_WITH_SALSA20_SHA1
                };
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
     *
     * Depending on the <tt>selectedCipherSuite</tt>, <tt>DefaultTlsServer</tt>
     * will require either <tt>rsaEncryptionCredentials</tt> or
     * <tt>rsaSignerCredentials</tt> neither of which is implemented by
     * <tt>DefaultTlsServer</tt>.
     */
    @Override
    protected TlsEncryptionCredentials getRSAEncryptionCredentials()
        throws IOException
    {
        if (rsaEncryptionCredentials == null)
        {
            rsaEncryptionCredentials
                = new DefaultTlsEncryptionCredentials(
                context,
                dtlsLayer.getCertificate(),
                dtlsLayer.getKeyPair().getPrivate());
        }
        return rsaEncryptionCredentials;
    }

    /**
     * {@inheritDoc}
     *
     * Depending on the <tt>selectedCipherSuite</tt>, <tt>DefaultTlsServer</tt>
     * will require either <tt>rsaEncryptionCredentials</tt> or
     * <tt>rsaSignerCredentials</tt> neither of which is implemented by
     * <tt>DefaultTlsServer</tt>.
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

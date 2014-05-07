/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.dtls;

import net.java.sip.communicator.util.Logger;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.*;
import org.bouncycastle.asn1.x500.style.*;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.*;
import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.generators.*;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.tls.*;
import org.bouncycastle.crypto.util.*;
import org.bouncycastle.operator.*;
import org.bouncycastle.operator.bc.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.version.*;
import org.jitsi.util.*;

import java.io.*;
import java.math.*;
import java.security.*;
import java.util.*;

/**
 * FIXME: class based on org.jitsi.impl.neomedia.transform.dtls.DtlsControlImpl
 *        and stripped from SRTP code to be used with SctpConnection.
 *        Common base class should be extracted.
 *        @author Pawel Domas
 *
 *
 * Implements {@link org.bouncycastle.crypto.tls.TlsServer} for the purposes of supporting DTLS-SRTP.
 *
 * @author Lyubomir Marinov
 */
public class DtlsLayer
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(DtlsLayer.class);

    /**
     * The number of milliseconds within a day i.e. 24 hours.
     */
    private static final long ONE_DAY = 1000L * 60L * 60L * 24L;

    /**
     * The certificate with which the local endpoint represented by this
     * instance authenticates its ends of DTLS sessions.
     */
    private org.bouncycastle.crypto.tls.Certificate certificate;

    /**
     * The private and public keys of {@link #certificate}.
     */
    private AsymmetricCipherKeyPair keyPair;

    /**
     * The fingerprint of {@link #certificate}.
     */
    private String localFingerprint;

    /**
     * The hash function of {@link #localFingerprint} (which is the same as the
     * digest algorithm of the signature algorithm of {@link #certificate} in
     * accord with RFC 4572).
     */
    private String localFingerprintHashFunction;

    /**
     * The fingerprints presented by the remote endpoint via the signaling path.
     */
    private Map<String,String> remoteFingerprints;


    /**
     * The table which maps half-<tt>byte</tt>s to their hex characters.
     */
    private static final char[] HEX_ENCODE_TABLE
        = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    /**
     * The value of the <tt>setup</tt> SDP attribute defined by RFC 4145
     * &quot;TCP-Based Media Transport in the Session Description Protocol
     * (SDP)&quot; which determines whether this instance acts as a DTLS client
     * or a DTLS server.
     */
    private DtlsControl.Setup setup;

    public DtlsLayer()
    {
        keyPair = generateKeyPair();

        org.bouncycastle.asn1.x509.Certificate x509Certificate
            = generateX509Certificate(generateCN(), keyPair);

        certificate
            = new org.bouncycastle.crypto.tls.Certificate(
            new org.bouncycastle.asn1.x509.Certificate[]
                {
                    x509Certificate
                });
        localFingerprintHashFunction = findHashFunction(x509Certificate);
        localFingerprint
            = computeFingerprint(
            x509Certificate,
            localFingerprintHashFunction);
    }

    /**
     * Generates a new self-signed certificate with a specific subject and a
     * specific pair of private and public keys.
     *
     * @param subject the subject (and issuer) of the new certificate to be
     * generated
     * @param keyPair the pair of private and public keys of the certificate to
     * be generated
     * @return a new self-signed certificate with the specified <tt>subject</tt>
     * and <tt>keyPair</tt>
     */
    private static org.bouncycastle.asn1.x509.Certificate
    generateX509Certificate(
        X500Name subject,
        AsymmetricCipherKeyPair keyPair)
    {
        try
        {
            long now = System.currentTimeMillis();
            Date notBefore = new Date(now - ONE_DAY);
            Date notAfter = new Date(now + 6 * ONE_DAY);
            X509v3CertificateBuilder builder
                = new X509v3CertificateBuilder(
                        /* issuer */ subject,
                        /* serial */ BigInteger.valueOf(now),
                        notBefore,
                        notAfter,
                        subject,
                        /* publicKeyInfo */
                        SubjectPublicKeyInfoFactory
                            .createSubjectPublicKeyInfo(
                                keyPair.getPublic()));
            AlgorithmIdentifier sigAlgId
                = new DefaultSignatureAlgorithmIdentifierFinder()
                .find("SHA1withRSA");
            AlgorithmIdentifier digAlgId
                = new DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId);
            ContentSigner signer
                = new BcRSAContentSignerBuilder(sigAlgId, digAlgId)
                .build(keyPair.getPrivate());

            return builder.build(signer).toASN1Structure();
        }
        catch (Throwable t)
        {
            if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
            else
            {
                logger.error(
                    "Failed to generate self-signed X.509 certificate",
                    t);
                if (t instanceof RuntimeException)
                    throw (RuntimeException) t;
                else
                    throw new RuntimeException(t);
            }
        }
    }

    /**
     * Generates a new pair of private and public keys.
     *
     * @return a new pair of private and public keys
     */
    private static AsymmetricCipherKeyPair generateKeyPair()
    {
        RSAKeyPairGenerator generator = new RSAKeyPairGenerator();

        generator.init(
            new RSAKeyGenerationParameters(
                new BigInteger("10001", 16),
                new SecureRandom(),
                1024,
                80));
        return generator.generateKeyPair();
    }

    /**
     * Generates a new subject for a self-signed certificate to be generated by
     * <tt>DtlsControlImpl</tt>.
     *
     * @return an <tt>X500Name</tt> which is to be used as the subject of a
     * self-signed certificate to be generated by <tt>DtlsControlImpl</tt>
     */
    private static X500Name generateCN()
    {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        String applicationName
            = System.getProperty(Version.PNAME_APPLICATION_NAME);
        String applicationVersion
            = System.getProperty(Version.PNAME_APPLICATION_VERSION);
        StringBuilder cn = new StringBuilder();

        if (!StringUtils.isNullOrEmpty(applicationName, true))
            cn.append(applicationName);
        if (!StringUtils.isNullOrEmpty(applicationVersion, true))
        {
            if (cn.length() != 0)
                cn.append(' ');
            cn.append(applicationVersion);
        }
        if (cn.length() == 0)
            cn.append(DtlsLayer.class.getName());
        builder.addRDN(BCStyle.CN, cn.toString());

        return builder.build();
    }

    /**
     * Gets the <tt>String</tt> representation of a fingerprint specified in the
     * form of an array of <tt>byte</tt>s in accord with RFC 4572.
     *
     * @param fingerprint an array of <tt>bytes</tt> which represents a
     * fingerprint the <tt>String</tt> representation in accord with RFC 4572
     * of which is to be returned
     * @return the <tt>String</tt> representation in accord with RFC 4572 of the
     * specified <tt>fingerprint</tt>
     */
    private static String toHex(byte[] fingerprint)
    {
        if (fingerprint.length == 0)
            throw new IllegalArgumentException("fingerprint");

        char[] chars = new char[3 * fingerprint.length - 1];

        for (int f = 0, fLast = fingerprint.length - 1, c = 0;
             f <= fLast;
             f++)
        {
            int b = fingerprint[f] & 0xff;

            chars[c++] = HEX_ENCODE_TABLE[b >>> 4];
            chars[c++] = HEX_ENCODE_TABLE[b & 0x0f];
            if (f != fLast)
                chars[c++] = ':';
        }
        return new String(chars);
    }

    /**
     * Determines the hash function i.e. the digest algorithm of the signature
     * algorithm of a specific certificate.
     *
     * @param certificate the certificate the hash function of which is to be
     * determined
     * @return the hash function of the specified <tt>certificate</tt>
     */
    private static String findHashFunction(
        org.bouncycastle.asn1.x509.Certificate certificate)
    {
        try
        {
            AlgorithmIdentifier sigAlgId = certificate.getSignatureAlgorithm();
            AlgorithmIdentifier digAlgId
                = new DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId);

            return
                BcDefaultDigestProvider.INSTANCE
                    .get(digAlgId)
                    .getAlgorithmName()
                    .toLowerCase();
        }
        catch (Throwable t)
        {
            if (t instanceof ThreadDeath)
            {
                throw (ThreadDeath) t;
            }
            else
            {
                logger.warn(
                    "Failed to find the hash function of the signature"
                        + " algorithm of a certificate!",
                    t);
                if (t instanceof RuntimeException)
                    throw (RuntimeException) t;
                else
                    throw new RuntimeException(t);
            }
        }
    }

    /**
     * Computes the fingerprint of a specific certificate using a specific
     * hash function.
     *
     * @param certificate the certificate the fingerprint of which is to be
     * computed
     * @param hashFunction the hash function to be used in order to compute the
     * fingerprint of the specified <tt>certificate</tt>
     * @return the fingerprint of the specified <tt>certificate</tt> computed
     * using the specified <tt>hashFunction</tt>
     */
    private static final String computeFingerprint(
        org.bouncycastle.asn1.x509.Certificate certificate,
        String hashFunction)
    {
        try
        {
            AlgorithmIdentifier digAlgId
                = new DefaultDigestAlgorithmIdentifierFinder().find(
                hashFunction.toUpperCase());
            Digest digest = BcDefaultDigestProvider.INSTANCE.get(digAlgId);
            byte[] in = certificate.getEncoded(ASN1Encoding.DER);
            byte[] out = new byte[digest.getDigestSize()];

            digest.update(in, 0, in.length);
            digest.doFinal(out, 0);

            return toHex(out);
        }
        catch (Throwable t)
        {
            if (t instanceof ThreadDeath)
            {
                throw (ThreadDeath) t;
            }
            else
            {
                logger.error("Failed to generate certificate fingerprint!", t);
                if (t instanceof RuntimeException)
                    throw (RuntimeException) t;
                else
                    throw new RuntimeException(t);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setRemoteFingerprints(Map<String,String> remoteFingerprints)
    {
        if (remoteFingerprints == null)
            throw new NullPointerException("remoteFingerprints");

        synchronized (this)
        {
            this.remoteFingerprints = remoteFingerprints;
            notifyAll();
        }
    }

    /**
     * Verifies and validates a specific certificate against the fingerprints
     * presented by the remote endpoint via the signaling path.
     *
     * @param certificate the certificate to be verified and validated against
     * the fingerprints presented by the remote endpoint via the signaling path
     * @throws Exception if the specified <tt>certificate</tt> failed to verify
     * and validate against the fingerprints presented by the remote endpoint
     * via the signaling path
     */
    private void verifyAndValidateCertificate(
        org.bouncycastle.asn1.x509.Certificate certificate)
        throws Exception
    {
        /*
         * RFC 4572 "Connection-Oriented Media Transport over the Transport
         * Layer Security (TLS) Protocol in the Session Description Protocol
         * (SDP)" defines that "[a] certificate fingerprint MUST be computed
         * using the same one-way hash function as is used in the certificate's
         * signature algorithm."
         */
        String hashFunction = findHashFunction(certificate);
        String fingerprint = computeFingerprint(certificate, hashFunction);

        /*
         * As RFC 5763 "Framework for Establishing a Secure Real-time Transport
         * Protocol (SRTP) Security Context Using Datagram Transport Layer
         * Security (DTLS)" states, "the certificate presented during the DTLS
         * handshake MUST match the fingerprint exchanged via the signaling path
         * in the SDP."
         */
        String remoteFingerprint;

        synchronized (this)
        {
            Map<String,String> remoteFingerprints = this.remoteFingerprints;

            if (remoteFingerprints == null)
            {
                throw new IOException(
                    "No fingerprints declared over the signaling"
                        + " path!");
            }
            else
            {
                remoteFingerprint = remoteFingerprints.get(hashFunction);
            }
        }
        if (remoteFingerprint == null)
        {
            throw new IOException(
                "No fingerprint declared over the signaling path with"
                    + " hash function: " + hashFunction + "!");
        }
        else if (!remoteFingerprint.equals(fingerprint))
        {
            throw new IOException(
                "Fingerprint " + remoteFingerprint
                    + " does not match the " + hashFunction
                    + "-hashed certificate " + fingerprint + "!");
        }
    }

    /**
     * Verifies and validates a specific certificate against the fingerprints
     * presented by the remote endpoint via the signaling path.
     *
     * @param certificate the certificate to be verified and validated against
     * the fingerprints presented by the remote endpoint via the signaling path
     * @return <tt>true</tt> if the specified <tt>certificate</tt> was
     * successfully verified and validated against the fingerprints presented by
     * the remote endpoint over the signaling path
     * @throws Exception if the specified <tt>certificate</tt> failed to verify
     * and validate against the fingerprints presented by the remote endpoint
     * over the signaling path
     */
    public boolean verifyAndValidateCertificate(
        org.bouncycastle.crypto.tls.Certificate certificate)
        throws Exception
    {
        boolean b = false;

        try
        {
            org.bouncycastle.asn1.x509.Certificate[] certificateList
                = certificate.getCertificateList();

            if (certificateList.length == 0)
            {
                throw new IllegalArgumentException(
                    "certificate.certificateList");
            }
            else
            {
                for (org.bouncycastle.asn1.x509.Certificate x509Certificate
                    : certificateList)
                {
                    verifyAndValidateCertificate(x509Certificate);
                }
                b = true;
            }
        }
        catch (Exception e)
        {
            /*
             * XXX Contrary to RFC 5763 "Framework for Establishing a Secure
             * Real-time Transport Protocol (SRTP) Security Context Using
             * Datagram Transport Layer Security (DTLS)", we do NOT want to tear
             * down the media session if the fingerprint does not match the
             * hashed certificate. We want to notify the user via the
             * SrtpListener.
             */
            // TODO Auto-generated method stub
            String message
                = "Failed to verify and/or validate a certificate offered over"
                + " the media path against fingerprints declared over the"
                + " signaling path!";
            String throwableMessage = e.getMessage();

            if ((throwableMessage == null) || (throwableMessage.length() == 0))
                logger.warn(message, e);
            else
                logger.warn(message + " " + throwableMessage);
        }
        return b;
    }

    public org.bouncycastle.crypto.tls.Certificate getCertificate()
    {
        return certificate;
    }

    public AsymmetricCipherKeyPair getKeyPair()
    {
        return keyPair;
    }

    /**
     * {@inheritDoc}
     */
    public String getLocalFingerprint()
    {
        return localFingerprint;
    }

    /**
     * {@inheritDoc}
     */
    public String getLocalFingerprintHashFunction()
    {
        return localFingerprintHashFunction;
    }

    /**
     * Sets the value of the <tt>setup</tt> SDP attribute defined by RFC 4145
     * &quot;TCP-Based Media Transport in the Session Description Protocol
     * (SDP)&quot; which determines whether this instance is to act as a DTLS
     * client or a DTLS server.
     *
     * @param setup the value of the <tt>setup</tt> SDP attribute to set on this
     * instance in order to determine whether this instance is to act as a DTLS
     * client or a DTLS server
     */
    public void setSetup(DtlsControl.Setup setup)
    {
        if (this.setup != setup)
        {
            this.setup = setup;
        }
        logger.error("!!! USING SETUP "+setup);
    }

    public DTLSTransport startDtls(DatagramTransport datagramTransport)
    {

        SecureRandom secureRandom = new SecureRandom();
        final DTLSProtocol dtlsProtocolObj;
        final TlsPeer tlsPeer;

        if (DtlsControl.Setup.ACTIVE.equals(setup))
        {
            dtlsProtocolObj = new DTLSClientProtocol(secureRandom);
            tlsPeer = new DataTlsClientImpl(this);
        }
        else
        {
            dtlsProtocolObj = new DTLSServerProtocol(secureRandom);
            tlsPeer = new DataTlsServerImpl(this);
        }

        DTLSTransport dtlsTransport = null;

        if (dtlsProtocolObj instanceof DTLSClientProtocol)
        {
            DTLSClientProtocol dtlsClientProtocol
                = (DTLSClientProtocol) dtlsProtocolObj;
            TlsClient tlsClient = (TlsClient) tlsPeer;

            for (int i = 3 - 1; i >= 0; i--)
            {
                try
                {
                    dtlsTransport
                        = dtlsClientProtocol.connect(
                            tlsClient,
                            datagramTransport);
                    break;
                }
                catch (IOException ioe)
                {
                    logger.error(ioe, ioe);
                }
            }
            return dtlsTransport;
        }
        else if (dtlsProtocolObj instanceof DTLSServerProtocol)
        {
            DTLSServerProtocol dtlsServerProtocol
                = (DTLSServerProtocol) dtlsProtocolObj;
            DataTlsServerImpl tlsServer = (DataTlsServerImpl) tlsPeer;

            for (int i = 3 - 1; i >= 0; i--)
            {
                try
                {
                    dtlsTransport
                        = dtlsServerProtocol.accept(
                            tlsServer,
                            datagramTransport);
                    break;
                }
                catch (IOException ioe)
                {
                    logger.error(ioe, ioe);
                }
            }
            return dtlsTransport;
        }
        else
            throw new IllegalStateException("dtlsProtocol");
    }

}

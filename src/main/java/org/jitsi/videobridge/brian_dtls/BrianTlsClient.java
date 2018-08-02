/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.videobridge.brian_dtls;

import org.bouncycastle.crypto.tls.*;
import org.jitsi.impl.neomedia.transform.dtls.*;
import org.jitsi.service.neomedia.*;

import java.io.*;
import java.util.*;

/**
 * @author bbaldino
 */
public class BrianTlsClient extends DefaultTlsClient
{
    private final byte[] mki = TlsUtils.EMPTY_BYTES;
    public static final int[] SRTP_PROTECTION_PROFILES
            = {
            SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
            SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32
    };
    private TlsCredentials clientCredentials;
    private DtlsControlImpl dtlsControl;

    public BrianTlsClient(DtlsControl dtlsControl) {
        this.dtlsControl = (DtlsControlImpl)dtlsControl;
    }

    @Override
    public TlsAuthentication getAuthentication()
    {
        return new TlsAuthentication()
        {
            public void notifyServerCertificate(org.bouncycastle.crypto.tls.Certificate serverCertificate)
                    throws IOException
            {
                System.out.println("BRIAN: notify server certificate");
                try
                {
                    dtlsControl.verifyAndValidateCertificate(serverCertificate);
                }
                catch (Exception e)
                {
                    System.out.println("BRIAN: Failed to verify and/or validate server certificate!" + e.toString());
                    if (e instanceof IOException)
                        throw (IOException) e;
                    else
                        throw new IOException(e);
                }
            }

            public TlsCredentials getClientCredentials(CertificateRequest certificateRequest)
                    throws IOException
            {
                System.out.println("BRIAN: getClientCredentials");
                CertificateInfo certificateInfo
                        = dtlsControl.getCertificateInfo();

                clientCredentials
                        = new DefaultTlsSignerCredentials(
                        context,
                        certificateInfo.getCertificate(),
                        certificateInfo.getKeyPair().getPrivate(),
                        new SignatureAndHashAlgorithm(
                                HashAlgorithm.sha1,
                                SignatureAlgorithm.rsa));
                return clientCredentials;
            }
        };
    }

    @Override
    public Hashtable getClientExtensions() throws IOException
    {
        Hashtable clientExtensions = super.getClientExtensions();
        if (TlsSRTPUtils.getUseSRTPExtension(clientExtensions) == null)
        {

            if (clientExtensions == null)
            {
                clientExtensions = new Hashtable();
            }

            TlsSRTPUtils.addUseSRTPExtension(clientExtensions, new UseSRTPData(SRTP_PROTECTION_PROFILES, mki));
        }
        return clientExtensions;
    }

    @Override
    public ProtocolVersion getClientVersion()
    {
        return ProtocolVersion.DTLSv10;
    }

    @Override
    public ProtocolVersion getMinimumVersion()
    {
        return ProtocolVersion.DTLSv10;
    }

    @Override
    public int[] getCipherSuites()
    {
        return new int[]
                {
/* core/src/main/java/org/bouncycastle/crypto/tls/DefaultTlsClient.java */
                        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
                        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                        CipherSuite.TLS_DHE_DSS_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_DHE_DSS_WITH_AES_128_CBC_SHA256,
                        CipherSuite.TLS_DHE_DSS_WITH_AES_128_CBC_SHA,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA256,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
                };
    }

    @Override
    public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Throwable cause)
    {
        PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
        out.println("BRIAN: DTLS client raised alert: " + AlertLevel.getText(alertLevel)
                + ", " + AlertDescription.getText(alertDescription));
        if (message != null)
        {
            out.println(message);
        }
        if (cause != null)
        {
            cause.printStackTrace(out);
        }
    }

    public void notifyAlertReceived(short alertLevel, short alertDescription)
    {
        PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
        out.println("BRIAN: DTLS client received alert: " + AlertLevel.getText(alertLevel)
                + ", " + AlertDescription.getText(alertDescription));
    }
}

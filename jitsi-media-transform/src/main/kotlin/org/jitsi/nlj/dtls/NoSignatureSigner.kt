package org.jitsi.nlj.dtls

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.operator.ContentSigner
import java.io.OutputStream

/**
 * A "signing" algorithm which produces an empty signature.  Based on
 * draft-davidben-x509-alg-none.
 * Should be treated as an unknown algorithm by all recipients, so any attempt to
 * validate it will return false.
 */
class NoSignatureSigner : ContentSigner {
    override fun getAlgorithmIdentifier(): AlgorithmIdentifier {
        return identifier
    }

    override fun getOutputStream(): OutputStream {
        return OutputStream.nullOutputStream()
    }

    override fun getSignature(): ByteArray {
        return ByteArray(0)
    }

    companion object {
        // A random OID we're squatting on, in the Columbia University number space.
        // (Registered in 1997, apparently unused since then.)
        // To be replaced with an IETF-assigned one if and when one is assigned.
        private val identifier = AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.113560.420.69"))
    }
}

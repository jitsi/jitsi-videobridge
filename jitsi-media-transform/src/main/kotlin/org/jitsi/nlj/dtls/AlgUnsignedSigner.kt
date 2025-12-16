package org.jitsi.nlj.dtls

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.operator.ContentSigner
import java.io.OutputStream

/**
 * A "signing" algorithm which produces an empty signature.  Defined by
 * draft-ietf-lamps-x509-alg-none, current implementation based on version -05.
 * Should be treated as an unknown algorithm by all recipients, so any attempt to
 * validate it will return false.
 */
class AlgUnsignedSigner : ContentSigner {
    override fun getAlgorithmIdentifier(): AlgorithmIdentifier {
        return identifier
    }

    override fun getOutputStream(): OutputStream {
        return OutputStream.nullOutputStream()
    }

    override fun getSignature(): ByteArray {
        /*
        The Certificate's signatureValue field MUST be a BIT STRING of length zero.
         */
        return ByteArray(0)
    }

    companion object {
        private val identifier = AlgorithmIdentifier(
            /*
                id-alg-unsigned OBJECT IDENTIFIER ::= {1 3 6 1 5 5 7 6 36}
             */
            ASN1ObjectIdentifier("1.3.6.1.5.5.7.6.36")
        )
    }
}

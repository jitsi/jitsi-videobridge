package org.jitsi.nlj.dtls

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
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
        /*
        The Certificate's signatureValue field MUST be a BIT STRING of length zero.
         */
        return ByteArray(0)
    }

    companion object {
        private val identifier = AlgorithmIdentifier(
            /*
             id-pkix OBJECT IDENTIFIER  ::= { iso(1) identified-organization(3)
                  dod(6) internet(1) security(5) mechanisms(5) pkix(7) }

              id-alg-noSignature OBJECT IDENTIFIER ::= {id-pkix id-alg(6) 2}
             */
            ASN1ObjectIdentifier("1.3.6.1.5.5.7.6.2"),
            /*
            The parameters for id-alg-noSignature MUST be present
               and MUST be encoded as NULL.
             */
            DERNull.INSTANCE
        )
    }
}

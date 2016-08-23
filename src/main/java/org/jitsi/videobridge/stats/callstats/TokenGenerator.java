/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.stats.callstats;

import net.java.sip.communicator.util.*;

import com.google.gson.*;
import com.google.gson.reflect.*;
import io.callstats.sdk.*;
import org.bouncycastle.util.encoders.*;
import org.jose4j.jwk.*;
import org.jose4j.jws.*;
import org.jose4j.jwt.*;
import org.jose4j.lang.*;

import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;

/**
 * Token generator for callstats.io third party authentication tokens.
 * @author Damian Minkov
 */
public class TokenGenerator
    implements ICallStatsTokenGenerator
{
    /**
     * The {@code Logger} used by the {@code CallStatsIOTransport} class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(TokenGenerator.class);

    /**
     * The callstats.io appID.
     */
    private final String appId;

    /**
     * The callstats.io keyID.
     */
    private final String keyId;

    /**
     * The callstats.io userID.
     */
    private final String userId;

    /**
     * The path to private key file..
     */
    private final String keyPath;

    /**
     * Creates new TokenGenerator.
     * @param appId the appID.
     * @param keyId the keyID.
     * @param userId the userID.
     */
    public TokenGenerator(String appId, String keyId, String userId,
        String keyPath)
    {
        this.appId = appId;
        this.keyId = keyId;
        this.userId = userId;
        this.keyPath = keyPath;
    }

    /**
     * Generates token.
     * @param forcenew currently not used.
     * @return the successfully generated token or null.
     */
    @Override
    public String generateToken(boolean forcenew)
    {
        try
        {
            JwtClaims claims = new JwtClaims();
            claims.setClaim("appID", this.appId);
            claims.setClaim("keyID", this.keyId);
            claims.setClaim("userID", this.userId);
            claims.setExpirationTimeMinutesInTheFuture(10);
            claims.setNotBeforeMinutesInThePast(10);

            JsonWebSignature jws = new JsonWebSignature();

            jws.setKey(readPrivateKey(keyPath));
            jws.setPayload(claims.toJson());
            jws.setAlgorithmHeaderValue(
                AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256);

            return jws.getCompactSerialization();
        }
        catch (Exception e)
        {
            logger.error("Error generating jwt token", e);
        }
        return null;
    }

    /**
     * Returns the private key to use.
     * @param keyPath the path to the key
     * @return the private key.
     * @throws IOException error reading file
     * @throws JoseException error parsing file
     */
    private static PrivateKey readPrivateKey(String keyPath)
        throws IOException,
               JoseException
    {
        Type mapType = new TypeToken<Map<String, String>>(){}.getType();
        Map<String, String> son = new Gson().fromJson(
            new FileReader(keyPath), mapType);

        EllipticCurveJsonWebKey jwk
            = new EllipticCurveJsonWebKey((Map<String, Object>)(Map)son);
        Base64Encoder enc = new Base64Encoder();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] encodedKey = jwk.getPrivateKey().getEncoded();
        enc.encode(encodedKey, 0, encodedKey.length, os);
        return jwk.getPrivateKey();
    }
}

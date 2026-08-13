package dev.billguard.coreapi.plaid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import com.plaid.client.model.JWKPublicKey;
import dev.billguard.coreapi.common.HttpException;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PlaidWebhookVerifier {
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(1);

    private final PlaidGateway plaidGateway;
    private final Clock clock;
    private final Cache<String, ECKey> keys = Caffeine.newBuilder()
        .maximumSize(20)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build();

    @Autowired
    public PlaidWebhookVerifier(PlaidGateway plaidGateway) {
        this(plaidGateway, Clock.systemUTC());
    }

    PlaidWebhookVerifier(PlaidGateway plaidGateway, Clock clock) {
        this.plaidGateway = plaidGateway;
        this.clock = clock;
    }

    public void verify(String verificationHeader, String rawBody) {
        if (verificationHeader == null || verificationHeader.isBlank()) {
            reject();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(verificationHeader);
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
                reject();
            }
            String keyId = jwt.getHeader().getKeyID();
            if (keyId == null || keyId.isBlank()) {
                reject();
            }
            ECKey key = keys.get(keyId, this::fetchKey);
            if (key == null || !jwt.verify(new ECDSAVerifier(key))) {
                reject();
            }
            Date issuedAt = jwt.getJWTClaimsSet().getIssueTime();
            Instant now = clock.instant();
            if (issuedAt == null
                || issuedAt.toInstant().isBefore(now.minus(MAX_AGE))
                || issuedAt.toInstant().isAfter(now.plus(FUTURE_SKEW))) {
                reject();
            }
            String claimedHash = jwt.getJWTClaimsSet().getStringClaim("request_body_sha256");
            if (!hashMatches(rawBody, claimedHash)) {
                reject();
            }
        } catch (HttpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidWebhook(exception);
        }
    }

    private ECKey fetchKey(String keyId) {
        try {
            JWKPublicKey key = plaidGateway.getWebhookVerificationKey(keyId);
            if (!"ES256".equals(key.getAlg()) || !"EC".equals(key.getKty()) || !"P-256".equals(key.getCrv())) {
                throw invalidWebhook(null);
            }
            return new ECKey.Builder(
                Curve.P_256,
                new Base64URL(key.getX()),
                new Base64URL(key.getY()))
                .keyID(key.getKid())
                .algorithm(JWSAlgorithm.ES256)
                .build();
        } catch (PlaidGatewayException exception) {
            throw invalidWebhook(exception);
        }
    }

    private boolean hashMatches(String rawBody, String claimedHash) throws NoSuchAlgorithmException {
        if (claimedHash == null || claimedHash.length() != 64) {
            return false;
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256")
                .digest(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] claimed = HexFormat.of().parseHex(claimedHash);
            return MessageDigest.isEqual(actual, claimed);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void reject() {
        throw invalidWebhook(null);
    }

    private HttpException invalidWebhook(Throwable cause) {
        HttpException exception = new HttpException(
            HttpStatus.UNAUTHORIZED, "Invalid Plaid webhook", "INVALID_WEBHOOK_SIGNATURE");
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }
}

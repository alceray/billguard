package dev.billguard.coreapi.plaid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.plaid.client.model.JWKPublicKey;
import dev.billguard.coreapi.common.HttpException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaidWebhookVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private static final String KEY_ID = "test-key";

    private PlaidGateway gateway;
    private ECKey privateKey;
    private PlaidWebhookVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        gateway = mock(PlaidGateway.class);
        privateKey = new ECKeyGenerator().generate();
        when(gateway.getWebhookVerificationKey(KEY_ID)).thenReturn(publicJwk(privateKey));
        verifier = new PlaidWebhookVerifier(gateway, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        String body = "{\"webhook_type\":\"TRANSACTIONS\"}";
        assertThatCode(() -> verifier.verify(sign(body, NOW), body)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNoneAlgorithm() {
        String none = "eyJhbGciOiJub25lIiwia2lkIjoidGVzdC1rZXkifQ.eyJpYXQiOjE3ODEwMDAwMDB9.";
        assertThatThrownBy(() -> verifier.verify(none, "{}"))
            .isInstanceOf(HttpException.class)
            .hasMessage("Invalid Plaid webhook");
    }

    @Test
    void rejectsOldIssuedAt() throws Exception {
        String body = "{}";
        assertThatThrownBy(() -> verifier.verify(sign(body, NOW.minusSeconds(601)), body))
            .isInstanceOf(HttpException.class);
    }

    @Test
    void rejectsBodyHashMismatch() throws Exception {
        assertThatThrownBy(() -> verifier.verify(sign("{\"value\":1}", NOW), "{\"value\":2}"))
            .isInstanceOf(HttpException.class);
    }

    private String sign(String body, Instant issuedAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issueTime(Date.from(issuedAt))
            .claim("request_body_sha256", sha256(body))
            .build();
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(), claims);
        jwt.sign(new ECDSASigner(privateKey));
        return jwt.serialize();
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private JWKPublicKey publicJwk(ECKey key) {
        ECKey publicKey = key.toPublicJWK();
        return new JWKPublicKey()
            .alg("ES256")
            .crv("P-256")
            .kid(KEY_ID)
            .kty("EC")
            .use("sig")
            .x(publicKey.getX().toString())
            .y(publicKey.getY().toString());
    }

    private static final class ECKeyGenerator {
        ECKey generate() throws Exception {
            return new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .generate();
        }
    }
}

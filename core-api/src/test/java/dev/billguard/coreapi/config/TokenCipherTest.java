package dev.billguard.coreapi.config;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsAndUsesARandomIv() {
        TokenCipher cipher = new TokenCipher(KEY, new SecureRandom());

        String first = cipher.encrypt("access-sandbox-secret");
        String second = cipher.encrypt("access-sandbox-secret");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("access-sandbox-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("access-sandbox-secret");
    }

    @Test
    void rejectsTamperedCiphertext() {
        TokenCipher cipher = new TokenCipher(KEY, new SecureRandom());
        String[] parts = cipher.encrypt("secret").split(":");
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
        ciphertext[ciphertext.length - 1] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Encrypted token authentication failed");
    }

    @Test
    void rejectsA128BitKeyAtStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new TokenCipher(shortKey, new SecureRandom()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exactly 32 bytes");
    }
}

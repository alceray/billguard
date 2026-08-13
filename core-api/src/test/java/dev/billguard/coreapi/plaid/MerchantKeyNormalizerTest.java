package dev.billguard.coreapi.plaid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantKeyNormalizerTest {
    private final MerchantKeyNormalizer normalizer = new MerchantKeyNormalizer();

    @Test
    void processorPrefixesAndStoreNumbersCollapseToOneKey() {
        assertThat(normalizer.normalize("SQ *BLUE BOTTLE #47", "ignored"))
            .isEqualTo("blue bottle")
            .isEqualTo(normalizer.normalize("SQ *BLUE BOTTLE", "ignored"));
    }

    @Test
    void stripsPhoneAndDomainNoise() {
        assertThat(normalizer.normalize("NETFLIX.COM 866-579-7172", "ignored"))
            .isEqualTo("netflix");
    }

    @Test
    void fallsBackToTransactionName() {
        assertThat(normalizer.normalize(null, "PAYPAL *SPOTIFY 1234"))
            .isEqualTo("spotify");
    }
}

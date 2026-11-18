package dev.billguard.coreapi.detection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationFingerprintTest {
    private final ClassificationFingerprint fingerprint = new ClassificationFingerprint();

    @Test
    void hashesOnlyTheExactSemanticModelInputs() {
        ClassificationCandidate original = new ClassificationCandidate(
            "merchant-one", "Merchant", "PRIMARY", null);
        ClassificationCandidate differentRoutingKey = new ClassificationCandidate(
            "merchant-two", "Merchant", "PRIMARY", null);
        ClassificationCandidate renamed = new ClassificationCandidate(
            "merchant-one", "Merchant renamed", "PRIMARY", null);
        ClassificationCandidate recategorized = new ClassificationCandidate(
            "merchant-one", "Merchant", "OTHER", null);

        assertThat(fingerprint.create(differentRoutingKey)).isEqualTo(fingerprint.create(original));
        assertThat(fingerprint.create(renamed)).isNotEqualTo(fingerprint.create(original));
        assertThat(fingerprint.create(recategorized)).isNotEqualTo(fingerprint.create(original));
    }
}

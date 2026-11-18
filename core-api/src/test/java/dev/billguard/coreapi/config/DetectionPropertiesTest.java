package dev.billguard.coreapi.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionPropertiesTest {
    @Test
    void defaultsToTwentyFiveAndAllowsAnOverride() {
        assertThat(new DetectionProperties(0).maxClassificationsPerRun()).isEqualTo(25);
        assertThat(new DetectionProperties(10).maxClassificationsPerRun()).isEqualTo(10);
    }
}

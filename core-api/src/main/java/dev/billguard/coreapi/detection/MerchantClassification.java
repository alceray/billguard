package dev.billguard.coreapi.detection;

public record MerchantClassification(
    String merchantKey,
    String classifierVersion,
    String fingerprint,
    ModelClassification classification,
    String model
) {
}

package dev.billguard.coreapi.plaid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public final class MerchantKeyNormalizer {
    private static final Pattern PROCESSOR_PREFIX = Pattern.compile(
        "^(?:(?:sq|tst|sp|pp)\\s*\\*|paypal\\s*\\*)\\s*",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile(
        "\\b(?:\\+?1[ .-]?)?(?:\\(?\\d{3}\\)?[ .-]?)\\d{3}[ .-]\\d{4}\\b");
    private static final Pattern DOMAIN_SUFFIX = Pattern.compile("\\.(?:com|net|org)\\b");
    private static final Pattern TRAILING_REFERENCE = Pattern.compile(
        "(?:\\s+(?:store\\s*)?[#*]?[a-z]*\\d[a-z0-9-]*)+$");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String normalize(String merchantName, String name) {
        String source = merchantName == null || merchantName.isBlank() ? name : merchantName;
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("A transaction merchant or name is required");
        }
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .trim();
        normalized = PROCESSOR_PREFIX.matcher(normalized).replaceFirst("");
        normalized = PHONE.matcher(normalized).replaceAll(" ");
        normalized = DOMAIN_SUFFIX.matcher(normalized).replaceAll(" ");
        normalized = TRAILING_REFERENCE.matcher(normalized).replaceFirst("");
        normalized = NON_ALPHANUMERIC.matcher(normalized).replaceAll(" ");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isBlank() ? "unknown-" + shortHash(source) : normalized;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

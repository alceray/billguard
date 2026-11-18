package dev.billguard.coreapi.detection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

@Component
public class ClassificationFingerprint {
    // Not the shared mapper: Jackson config changes would silently invalidate every
    // cached classification, and classifier_version does not cover that.
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public String create(ClassificationCandidate candidate) {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("merchant_name", candidate.merchantName());
        input.put("pfc_primary", candidate.pfcPrimary());
        input.put("pfc_detailed", candidate.pfcDetailed());
        try {
            byte[] canonicalJson = MAPPER.writeValueAsBytes(input);
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonicalJson));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint classification input", exception);
        }
    }
}

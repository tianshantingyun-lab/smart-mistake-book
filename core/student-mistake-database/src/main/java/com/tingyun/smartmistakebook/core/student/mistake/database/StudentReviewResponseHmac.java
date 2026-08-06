package com.tingyun.smartmistakebook.core.student.mistake.database;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Package-private cryptographic primitive used only by the student owner and its tests. */
final class StudentReviewResponseHmac {
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] DOMAIN =
            "student-review-response-binding-v1".getBytes(StandardCharsets.UTF_8);

    private StudentReviewResponseHmac() {}

    static String issue(
            SecretKey key,
            String authenticatedBindingContextFingerprint) {
        requireSha256(authenticatedBindingContextFingerprint);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(DOMAIN);
            mac.update((byte) 0);
            byte[] binding =
                    mac.doFinal(
                            authenticatedBindingContextFingerprint.getBytes(StandardCharsets.UTF_8));
            return lowerHex(binding);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Review response binding could not be issued", failure);
        }
    }

    private static void requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Review response-binding scope must be SHA-256");
        }
    }

    private static String lowerHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }
}

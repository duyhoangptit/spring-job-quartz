package com.system.reportjob.shared.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/** Redacts known-sensitive field values out of log-bound text (JSON bodies, form bodies, query strings). */
public final class SensitiveDataMasker {

    public static final String MASKED_VALUE = "***";

    private static final List<String> SENSITIVE_FIELD_NAMES = List.of(
            "password", "otp", "otpCode", "pin", "token", "accessToken", "refreshToken", "secret", "cvv", "cardNumber");

    private static final Pattern JSON_FIELD_PATTERN = Pattern.compile("(?i)(\"(?:"
            + String.join("|", SENSITIVE_FIELD_NAMES)
            + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]]+)");

    private static final Pattern FORM_FIELD_PATTERN =
            Pattern.compile("(?i)\\b(" + String.join("|", SENSITIVE_FIELD_NAMES) + ")=[^&\\s]*");

    private SensitiveDataMasker() {}

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked =
                JSON_FIELD_PATTERN.matcher(text).replaceAll(match -> match.group(1) + "\"" + MASKED_VALUE + "\"");
        return FORM_FIELD_PATTERN.matcher(masked).replaceAll(match -> match.group(1) + "=" + MASKED_VALUE);
    }
}

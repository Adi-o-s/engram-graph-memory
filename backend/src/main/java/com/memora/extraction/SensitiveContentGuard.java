package com.memora.extraction;

import java.util.List;
import java.util.regex.Pattern;

/** Detects text that looks like a credential so it is never stored as memory. */
public final class SensitiveContentGuard {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[_ -]?key|access[_ -]?key|token)\\s*(is|=|:)\\s*\\S+"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bmem_[A-Za-z0-9_-]{30,}"));

    private SensitiveContentGuard() {
    }

    public static boolean looksSensitive(String text) {
        if (text == null) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}

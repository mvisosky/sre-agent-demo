package com.example.sre.common.security;

import java.util.regex.Pattern;

/**
 * Fences untrusted data (email text, LLM-extracted fields, retrieved playbook text) inside
 * clearly labelled blocks so the system prompt can instruct the model to treat it as data only.
 */
public final class UntrustedContent {

    private static final Pattern FENCE_TAG = Pattern.compile("</?\\s*untrusted[-_a-z]*[^>]*>", Pattern.CASE_INSENSITIVE);

    private UntrustedContent() {}

    public static String wrap(String label, Object value) {
        String text = value == null ? "" : value.toString();
        // Remove anything that could close/spoof our fence, and non-printable control chars.
        text = FENCE_TAG.matcher(text).replaceAll("[removed-tag]");
        text = text.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "");
        return "<untrusted-" + label + ">\n" + text + "\n</untrusted-" + label + ">";
    }
}

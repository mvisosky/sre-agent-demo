package com.example.sre.common.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic detector for common prompt-injection / jailbreak / delimiter-spoofing patterns and
 * control characters. This is one defensive layer (defence in depth), not a complete classifier.
 */
public final class PromptInjectionDetector {

    /** A named rule. Only the name is ever logged, never the matched text. */
    public record Rule(String name, Pattern pattern) {}

    /** Name of the rule that flags spoofed untrusted-content fence tags. */
    public static final String FENCE_RULE = "delimiter-spoofing-untrusted-fence";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Rule> RULES = List.of(
        new Rule("override-instructions", Pattern.compile(
            "\\b(ignore|disregard|forget|override|bypass)\\b[^\\n]{0,40}\\b(all|any|the|your|previous|prior|above|earlier|system)\\b[^\\n]{0,40}\\b(instructions?|prompts?|rules?|guidelines?|directives?|context)\\b", FLAGS)),
        new Rule("system-prompt-extraction", Pattern.compile(
            "\\b(reveal|show|print|output|repeat|leak|display|tell me|what (is|are) (your|the))\\b[^\\n]{0,40}\\b(system|initial|hidden|original|internal)\\s+(prompt|instructions?|message)", FLAGS)),
        new Rule("role-reassignment", Pattern.compile(
            "\\b(you are now|from now on,? you (are|will|must|shall)|pretend (to be|you are)|new instructions?:)\\b", FLAGS)),
        new Rule("jailbreak", Pattern.compile(
            "\\b(jailbreak|DAN mode|developer mode|do anything now)\\b", FLAGS)),
        new Rule("delimiter-spoofing-role-tag", Pattern.compile(
            "(?m)^\\s*(#{1,6}\\s*)?(system|assistant|developer)\\s*:\\s|<\\|?\\s*/?\\s*(system|assistant|im_start|im_end|endoftext)\\s*\\|?>|\\[/?(INST|SYS)]|<<\\s*/?SYS\\s*>>", FLAGS)),
        new Rule(FENCE_RULE, Pattern.compile(
            "</?\\s*untrusted[-_a-z]*[^>]*>", FLAGS)),
        new Rule("unauthorized-tool-manipulation", Pattern.compile(
            "\\b(call|invoke|execute|run|use)\\b[^\\n]{0,30}\\b(tool|function)s?\\b[^\\n]{0,60}\\b(with|using)\\b[^\\n]{0,30}\\b(arguments?|parameters?|params?)\\b", FLAGS))
    );

    private PromptInjectionDetector() {}

    /** Returns the name of the first matching rule, if any. */
    public static Optional<String> detect(String text) {
        return detect(text, Set.of());
    }

    /** Like {@link #detect(String)} but skips the named rules (e.g. when the caller already neutralised them). */
    public static Optional<String> detect(String text, Set<String> ignoredRules) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        if (containsDisallowedControlChars(text)) {
            return Optional.of("control-characters");
        }
        for (Rule rule : RULES) {
            if (ignoredRules.contains(rule.name())) {
                continue;
            }
            if (rule.pattern().matcher(text).find()) {
                return Optional.of(rule.name());
            }
        }
        return Optional.empty();
    }

    /** True for control chars other than tab/LF/CR, plus zero-width / bidi override characters. */
    public static boolean containsDisallowedControlChars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (Character.isISOControl(c)) {
                return true;
            }
            // Zero-width space/non-joiner, LRM/RLM, bidi embeddings/overrides/isolates, BOM.
            // ZWJ (U+200D) is deliberately allowed: it is required by legitimate emoji sequences.
            if (c == '\u200B' || c == '\u200C' || c == '\u200E' || c == '\u200F'
                || (c >= '\u202A' && c <= '\u202E')
                || (c >= '\u2066' && c <= '\u2069') || c == '\uFEFF') {
                return true;
            }
        }
        return false;
    }
}

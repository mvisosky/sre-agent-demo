package com.example.sre.common.security;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Heuristic detector for common prompt-injection / jailbreak / delimiter-spoofing patterns and
 * control characters. This is one defensive layer (defence in depth), not a complete classifier.
 */
public final class PromptInjectionDetector {

    /** A named rule. Only the name is ever logged, never the matched text. */
    public record Rule(String name, Pattern pattern) {}

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Rule> RULES = List.of(
        new Rule("override-instructions", Pattern.compile(
            "\\b(ignore|disregard|forget|override|bypass)\\b[^\\n]{0,40}\\b(all|any|the|your|previous|prior|above|earlier|system)\\b[^\\n]{0,40}\\b(instructions?|prompts?|rules?|guidelines?|directives?|context)\\b", FLAGS)),
        new Rule("system-prompt-extraction", Pattern.compile(
            "\\b(reveal|show|print|output|repeat|leak|display|tell me|what (is|are))\\b[^\\n]{0,40}\\b(system|initial|hidden|original|internal)\\s+(prompt|instructions?|message)", FLAGS)),
        new Rule("role-reassignment", Pattern.compile(
            "\\b(you are now|from now on,? you|act as|pretend (to be|you are)|new instructions?:)\\b", FLAGS)),
        new Rule("jailbreak", Pattern.compile(
            "\\b(jailbreak|DAN mode|developer mode|do anything now)\\b", FLAGS)),
        new Rule("delimiter-spoofing-role-tag", Pattern.compile(
            "(?m)^\\s*(#{1,6}\\s*)?(system|assistant|developer)\\s*:\\s|<\\|?\\s*/?\\s*(system|assistant|im_start|im_end|endoftext)\\s*\\|?>|\\[/?(INST|SYS)]|<<\\s*/?SYS\\s*>>", FLAGS)),
        new Rule("delimiter-spoofing-untrusted-fence", Pattern.compile(
            "</?\\s*untrusted[-_a-z]*[^>]*>", FLAGS)),
        new Rule("unauthorized-tool-manipulation", Pattern.compile(
            "\\b(call|invoke|execute|run|use)\\b[^\\n]{0,30}\\b(tool|function)s?\\b[^\\n]{0,60}\\b(with|using)\\b[^\\n]{0,30}\\b(arguments?|parameters?|params?)\\b", FLAGS))
    );

    private PromptInjectionDetector() {}

    /** Returns the name of the first matching rule, if any. */
    public static Optional<String> detect(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        if (containsDisallowedControlChars(text)) {
            return Optional.of("control-characters");
        }
        for (Rule rule : RULES) {
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
            if ((c >= '​' && c <= '‏') || (c >= '‪' && c <= '‮')
                || (c >= '⁦' && c <= '⁩') || c == '﻿') {
                return true;
            }
        }
        return false;
    }
}

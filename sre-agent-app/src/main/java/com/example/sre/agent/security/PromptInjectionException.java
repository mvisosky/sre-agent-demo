package com.example.sre.agent.security;

/** Thrown when a prompt is blocked by the guardrail. Message never contains the offending content. */
public class PromptInjectionException extends RuntimeException {

    private final String rule;

    public PromptInjectionException(String rule) {
        super("Request blocked: input matched prompt-injection rule '" + rule + "'");
        this.rule = rule;
    }

    public String getRule() {
        return rule;
    }
}

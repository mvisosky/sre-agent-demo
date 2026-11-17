package com.example.sre.agent.security;

import com.example.sre.common.security.PromptInjectionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * Inspects user-role messages (never the trusted system prompt) before they reach the LLM and
 * blocks known injection / jailbreak / delimiter-spoofing patterns. Logs only the rule name,
 * never the payload, to avoid leaking PII or attacker-controlled log content.
 */
public class PromptGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptGuardrailAdvisor.class);

    private final int order;

    public PromptGuardrailAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        check(request);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        try {
            check(request);
        } catch (PromptInjectionException e) {
            return Flux.error(e);
        }
        return chain.nextStream(request);
    }

    private void check(ChatClientRequest request) {
        for (Message message : request.prompt().getInstructions()) {
            if (message.getMessageType() != MessageType.USER) {
                continue;
            }
            Optional<String> rule = PromptInjectionDetector.detect(message.getText());
            if (rule.isPresent()) {
                log.warn("SECURITY: prompt blocked by guardrail [rule={}, length={}]",
                    rule.get(), message.getText() == null ? 0 : message.getText().length());
                throw new PromptInjectionException(rule.get());
            }
        }
    }

    @Override
    public String getName() {
        return "PromptGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }
}

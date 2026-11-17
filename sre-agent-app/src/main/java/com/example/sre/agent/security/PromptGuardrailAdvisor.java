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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspects the raw email payload inside user-role messages (never the trusted system prompt, playbooks or extracted fields) before they reach the LLM and
 * blocks known injection / jailbreak / delimiter-spoofing patterns. Logs only the rule name,
 * never the payload, to avoid leaking PII or attacker-controlled log content.
 */
public class PromptGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptGuardrailAdvisor.class);

    /** Matches the raw-email fence emitted by {@code UntrustedContent.wrap("email", ...)}. */
    private static final Pattern EMAIL_FENCE =
        Pattern.compile("<untrusted-email>\\n(.*?)\\n</untrusted-email>", Pattern.DOTALL);

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
            String text = message.getText();
            if (text == null) {
                continue;
            }
            // Only the raw email is attacker-controlled. Alert-context fields are Claude-extracted from an
            // already-scanned email and playbooks are validated at ingest, so scanning them here would
            // reject legitimate runbook phrasing. The app's own fence tags are not an attack, and
            // UntrustedContent.wrap already neutralises spoofed ones inside the payload.
            Matcher m = EMAIL_FENCE.matcher(text);
            while (m.find()) {
                Optional<String> rule = PromptInjectionDetector.detect(m.group(1), Set.of(PromptInjectionDetector.FENCE_RULE));
                if (rule.isPresent()) {
                    log.warn("SECURITY: prompt blocked by guardrail [rule={}, length={}]", rule.get(), text.length());
                    throw new PromptInjectionException(rule.get());
                }
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

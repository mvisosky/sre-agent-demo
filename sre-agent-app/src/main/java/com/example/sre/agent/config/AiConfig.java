package com.example.sre.agent.config;

import com.example.sre.agent.security.PromptGuardrailAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AlertDeduplicationProperties.class)
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultAdvisors(new PromptGuardrailAdvisor(Ordered.HIGHEST_PRECEDENCE + 100))
            .build();
    }

    /**
     * Newer Anthropic Claude models (e.g., claude-sonnet-5-5) reject requests that include
     * the deprecated `temperature` parameter, which Spring AI's AnthropicChatProperties
     * sets to a non-null default (0.8) even when omitted from application.yml.
     */
    @Bean
    public static BeanPostProcessor anthropicTemperatureClearer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean.getClass().getSimpleName().contains("AnthropicChat")) {
                    clearTemperature(bean);
                    try {
                        Method getOptions = bean.getClass().getMethod("getOptions");
                        Object options = getOptions.invoke(bean);
                        if (options != null) {
                            clearTemperature(options);
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        var field = bean.getClass().getDeclaredField("defaultOptions");
                        field.setAccessible(true);
                        Object defaultOptions = field.get(bean);
                        if (defaultOptions != null) {
                            clearTemperature(defaultOptions);
                        }
                    } catch (Exception ignored) {
                    }
                }
                return bean;
            }

            private void clearTemperature(Object target) {
                try {
                    Method setTemperature = target.getClass().getMethod("setTemperature", Double.class);
                    setTemperature.invoke(target, new Object[]{null});
                } catch (Exception ignored) {
                }
            }
        };
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}

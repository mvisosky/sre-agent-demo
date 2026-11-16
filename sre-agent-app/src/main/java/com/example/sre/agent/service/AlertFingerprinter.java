package com.example.sre.agent.service;

import com.example.sre.common.model.EmailWebhookRequest;
import com.example.sre.common.model.ExtractedAlertInfo;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class AlertFingerprinter {

    // Dynamic noise patterns stripped during Stage 1 pre-LLM fingerprinting
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_TIMESTAMP_PATTERN = Pattern.compile(
        "\\b\\d{4}-\\d{2}-\\d{2}[tT ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[zZ]|[+-]\\d{2}:?\\d{2})?\\b");
    private static final Pattern K8S_POD_SUFFIX_PATTERN = Pattern.compile(
        "-[0-9a-f]{6,10}-[a-z0-9]{4,6}\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern POD_INSTANCE_PATTERN = Pattern.compile(
        "\\b(pod|instance|node|host|container|tx|req|trace|alert)[-_:#]?[0-9a-f]{4,}\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    private static final Pattern METRIC_VALUE_PATTERN = Pattern.compile(
        "\\b\\d[\\d,.]*\\s*(?:%|(?:ms|s|sec|seconds|m|min|minutes|h|hours|mb|gb|kb|req/s|msg/min)\\b)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TICKET_OR_BUILD_ID_PATTERN = Pattern.compile(
        "(?:#\\d+|\\b(?:id|txid|seq|attempt|count|lag)\\s*[=:]\\s*[\\w-]+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LARGE_NUMBER_PATTERN = Pattern.compile(
        "\\b\\d{1,3}(?:,\\d{3})+\\b|\\b\\d{4,}\\b");
    private static final Pattern NON_ALPHANUM_PATTERN = Pattern.compile("[^a-z0-9\\s-]");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "that", "this", "are", "was", "were",
        "has", "have", "had", "been", "alert", "firing", "detected", "reported",
        "service", "prod", "production", "env", "environment", "high", "critical",
        "warning", "error", "errors", "elevated", "above", "threshold", "over"
    );

    /**
     * Stage 1 (Pre-LLM): Computes a normalized fingerprint directly from the raw email subject
     * (and key signature line) without calling the LLM.
     */
    public String computeStage1Fingerprint(EmailWebhookRequest request) {
        if (request == null) {
            return "s1:empty";
        }
        String rawSubject = request.subject() != null ? request.subject() : "";
        String normalizedSubject = normalizeDynamicNoise(rawSubject);

        // If subject is overly generic (< 12 chars), include the first non-empty line of the body
        if (normalizedSubject.length() < 12 && request.body() != null && !request.body().isBlank()) {
            String firstLine = request.body().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
            normalizedSubject = (normalizedSubject + " " + normalizeDynamicNoise(firstLine)).trim();
        }

        return "s1:" + normalizedSubject;
    }

    /**
     * Stage 2 (Post-Extraction): Computes the service + environment scope key for active incidents.
     */
    public String computeServiceScopeKey(ExtractedAlertInfo alertInfo) {
        if (alertInfo == null) {
            return "s2:unknown-service:default";
        }
        String service = (alertInfo.serviceName() == null || alertInfo.serviceName().isBlank())
            ? "unknown-service"
            : alertInfo.serviceName().trim().toLowerCase(Locale.ROOT);
        String env = normalizeEnvironment(alertInfo.environment());
        return "s2:" + service + ":" + env;
    }

    /**
     * Evaluates whether a newly extracted alert should be correlated into an existing active
     * incident for the same (serviceName, environment).
     */
    public boolean isCorrelatedWithActiveIncident(
            ExtractedAlertInfo candidate,
            ExtractedAlertInfo active,
            double similarityThreshold,
            boolean correlateSameServiceCritical) {
        if (candidate == null || active == null) {
            return false;
        }
        if (!computeServiceScopeKey(candidate).equals(computeServiceScopeKey(active))) {
            return false;
        }

        Set<String> candidateTokens = extractSemanticTokens(candidate);
        Set<String> activeTokens = extractSemanticTokens(active);

        double similarity = calculateJaccardSimilarity(candidateTokens, activeTokens);
        if (similarity >= similarityThreshold) {
            return true;
        }

        if (correlateSameServiceCritical && isCriticalOrHigh(candidate.severity()) && isCriticalOrHigh(active.severity())) {
            return true;
        }

        return false;
    }

    public double calculateJaccardSimilarity(Set<String> setA, Set<String> setB) {
        if (setA == null || setB == null || setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    public Set<String> extractSemanticTokens(ExtractedAlertInfo info) {
        if (info == null) {
            return Set.of();
        }
        StringBuilder combined = new StringBuilder();
        appendIfPresent(combined, info.alertTitle());
        appendIfPresent(combined, info.errorSummary());
        if (info.symptoms() != null) {
            info.symptoms().forEach(s -> appendIfPresent(combined, s));
        }
        if (info.affectedEndpointsOrResources() != null) {
            info.affectedEndpointsOrResources().forEach(r -> appendIfPresent(combined, r));
        }

        String cleaned = normalizeDynamicNoise(combined.toString());
        return Arrays.stream(MULTI_SPACE_PATTERN.split(cleaned))
            .map(String::trim)
            .filter(token -> token.length() >= 3)
            .filter(token -> !STOP_WORDS.contains(token))
            .collect(Collectors.toSet());
    }

    public String normalizeDynamicNoise(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String result = text.toLowerCase(Locale.ROOT);
        result = UUID_PATTERN.matcher(result).replaceAll(" ");
        result = ISO_TIMESTAMP_PATTERN.matcher(result).replaceAll(" ");
        result = K8S_POD_SUFFIX_PATTERN.matcher(result).replaceAll("");
        result = POD_INSTANCE_PATTERN.matcher(result).replaceAll("$1");
        result = IPV4_PATTERN.matcher(result).replaceAll(" ");
        result = METRIC_VALUE_PATTERN.matcher(result).replaceAll(" ");
        result = TICKET_OR_BUILD_ID_PATTERN.matcher(result).replaceAll(" ");
        result = LARGE_NUMBER_PATTERN.matcher(result).replaceAll(" ");
        result = NON_ALPHANUM_PATTERN.matcher(result).replaceAll(" ");
        result = MULTI_SPACE_PATTERN.matcher(result).replaceAll(" ").trim();
        return result;
    }

    private String normalizeEnvironment(String env) {
        if (env == null || env.isBlank()) {
            return "prod";
        }
        String norm = env.trim().toLowerCase(Locale.ROOT);
        if (norm.startsWith("prod")) {
            return "prod";
        }
        return norm;
    }

    private boolean isCriticalOrHigh(String severity) {
        if (severity == null || severity.isBlank()) {
            return true;
        }
        String s = severity.trim().toUpperCase(Locale.ROOT);
        return s.contains("P1") || s.contains("P2") || s.contains("CRIT") || s.contains("HIGH") || s.contains("SEV1") || s.contains("SEV-1");
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(' ').append(value);
        }
    }
}

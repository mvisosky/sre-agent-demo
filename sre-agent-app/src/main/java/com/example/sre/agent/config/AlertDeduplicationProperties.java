package com.example.sre.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "sre.agent.deduplication")
public class AlertDeduplicationProperties {

    /**
     * Master switch to enable or disable two-stage alert deduplication and flood suppression.
     */
    private boolean enabled = true;

    /**
     * Time window during which duplicate or related alerts for an active incident are suppressed
     * and correlated into the primary incident report.
     */
    private Duration window = Duration.ofMinutes(15);

    /**
     * Minimum Jaccard token similarity threshold (0.0 to 1.0) across extracted symptoms,
     * error summary, and affected resources to correlate differently worded alerts in Stage 2.
     */
    private double symptomSimilarityThreshold = 0.20;

    /**
     * When true, any P1/CRITICAL alert targeting the same (serviceName + environment) as an
     * already-active incident within the suppression window is automatically correlated.
     */
    private boolean correlateSameServiceCritical = true;

    /**
     * Maximum number of active incidents retained in the in-memory deduplication registry.
     */
    private int maxActiveIncidents = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public double getSymptomSimilarityThreshold() {
        return symptomSimilarityThreshold;
    }

    public void setSymptomSimilarityThreshold(double symptomSimilarityThreshold) {
        this.symptomSimilarityThreshold = symptomSimilarityThreshold;
    }

    public boolean isCorrelateSameServiceCritical() {
        return correlateSameServiceCritical;
    }

    public void setCorrelateSameServiceCritical(boolean correlateSameServiceCritical) {
        this.correlateSameServiceCritical = correlateSameServiceCritical;
    }

    public int getMaxActiveIncidents() {
        return maxActiveIncidents;
    }

    public void setMaxActiveIncidents(int maxActiveIncidents) {
        this.maxActiveIncidents = maxActiveIncidents;
    }
}

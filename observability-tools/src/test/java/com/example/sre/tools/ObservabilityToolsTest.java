package com.example.sre.tools;

import com.example.sre.common.model.IncidentReport.InvestigationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityToolsTest {

    private ObservabilityTools tools;

    @BeforeEach
    void setUp() {
        tools = new ObservabilityTools();
        tools.resetTrace();
    }

    @Test
    void shouldReturnTelemetryAndRecordInvestigationStepsForPaymentService() {
        String health = tools.getServiceHealth("payment-service", scope("payment-service"));
        String pool = tools.getDatabaseConnectionPoolStats("payment-service", scope("payment-service"));
        String logs = tools.queryServiceLogs("payment-service", "ERROR", 5, scope("payment-service"));
        String deployments = tools.getRecentDeployments("payment-service", scope("payment-service"));

        assertThat(health).contains("DEGRADED");
        assertThat(pool).contains("Active Connections: 20 / 20");
        assertThat(logs).contains("HikariPool-1 - Connection is not available");
        assertThat(deployments).contains("v2.14.2");

        List<InvestigationStep> steps = tools.getRecordedSteps();
        assertThat(steps).hasSize(4);
        assertThat(steps.get(0).stepNumber()).isEqualTo(1);
        assertThat(steps.get(0).toolUsed()).isEqualTo("getServiceHealth(payment-service)");
        assertThat(steps.get(1).toolUsed()).isEqualTo("getDatabaseConnectionPoolStats(payment-service)");
    }

    private static ToolContext scope(String service) {
        return new ToolContext(Map.of(ObservabilityTools.ALLOWED_SERVICE_KEY, service));
    }

    @Test
    void shouldRejectOutOfScopeServiceWithErrorAndRecordNothing() {
        // Injected instruction made the model ask for a different service
        String health = tools.getServiceHealth("auth-service", scope("payment-service"));

        assertThat(health).isEqualTo(ObservabilityTools.SCOPE_ERROR);
        assertThat(tools.getRecordedSteps()).isEmpty();
    }

    @Test
    void shouldFailClosedWhenNoServiceIsBound() {
        assertThat(tools.getServiceHealth("payment-service", null)).isEqualTo(ObservabilityTools.NO_SCOPE_ERROR);
        assertThat(tools.getServiceMetrics("payment-service", "15m", new ToolContext(Map.of())))
            .isEqualTo(ObservabilityTools.NO_SCOPE_ERROR);
        assertThat(tools.getRecordedSteps()).isEmpty();
    }

    @Test
    void shouldRejectNullServiceNameEvenWhenScoped() {
        assertThat(tools.getServiceHealth(null, scope("payment-service"))).isEqualTo(ObservabilityTools.SCOPE_ERROR);
    }
}

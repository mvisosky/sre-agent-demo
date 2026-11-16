package com.example.sre.tools;

import com.example.sre.common.model.IncidentReport.InvestigationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        String health = tools.getServiceHealth("payment-service");
        String pool = tools.getDatabaseConnectionPoolStats("payment-service");
        String logs = tools.queryServiceLogs("payment-service", "ERROR", 5);
        String deployments = tools.getRecentDeployments("payment-service");

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
}

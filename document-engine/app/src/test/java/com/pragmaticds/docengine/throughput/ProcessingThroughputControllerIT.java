package com.pragmaticds.docengine.throughput;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Requests run as ORG_DEV via DevTenantFilter; with nothing measured the seeded numbers come back. */
class ProcessingThroughputControllerIT extends AbstractPostgresIT {

    @Autowired TestRestTemplate rest;

    @BeforeEach
    void clearOrgDevMeasurements() {
        // Same shared-container hazard ProcessingThroughputServiceIT guards against: this test's
        // "nothing measured" assumption only holds if no earlier IT in the full suite left
        // completed ORG_DEV jobs/pages behind (JobTimeBudgetIT and JobCancelIT both run real
        // pipelines to a terminal status for ORG_DEV). See AbstractPostgresIT#wipeOrgData for the
        // FK ordering.
        wipeOrgData(ORG_DEV, ORG_OTHER);
    }

    @Test
    void get_throughput_returns_the_five_rate_fields() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/v1/processing/throughput", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("ocrSecondsPerPage").asDouble()).isEqualTo(70.0);
        assertThat(body.get("otherSecondsPerPage").asDouble()).isEqualTo(2.0);
        assertThat(body.get("fixedSecondsPerJob").asDouble()).isEqualTo(10.0);
        assertThat(body.get("sampleJobs").asInt()).isZero();
        assertThat(body.get("measured").asBoolean()).isFalse();
    }
}

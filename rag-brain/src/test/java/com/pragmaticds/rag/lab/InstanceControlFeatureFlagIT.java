package com.pragmaticds.rag.lab;

import com.pragmaticds.rag.lab.config.InstanceControlStartupValidator;
import com.pragmaticds.rag.lab.connect.DocumentManagerRunService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.instance.InstancePromotionService;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService;
import com.pragmaticds.rag.lab.run.RunGroupDispatcher;
import com.pragmaticds.rag.lab.run.RunGroupPoller;
import com.pragmaticds.rag.lab.run.RunGroupRecoveryService;
import com.pragmaticds.rag.lab.run.RunGroupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The everything-off deployment — the one every production environment is running today — must be
 * exactly the application that shipped before the instance control plane existed: no instance
 * beans, no instance routes, no dispatcher thread, and every legacy route intact.
 *
 * <p>This is the affirmative half of the flag matrix. The dependent combinations — a child flag
 * on while its parent is off, or a capability on without its prerequisites — refuse to
 * <em>start</em>, and a context that refuses to start cannot be observed from a
 * {@code @SpringBootTest} inside it; {@code InstanceControlStartupValidatorTest} proves that half
 * combination by combination against the validator's plain constructor, including that refusals
 * name property keys and never configured values.
 *
 * <p>Absence here is bean absence, not disabled-but-present: {@code @ConditionalOnProperty} means
 * the classes are never constructed, so there is no code path a request could reach.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=false",
        "ragbrain.instances.promotion-enabled=false",
        "ragbrain.instances.connector-enabled=false",
        "ragbrain.instances.execution.enabled=false",
        "ragbrain.lab.enabled=false",
        "ragbrain.rag.admin.api-key=flag-matrix-it-key"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstanceControlFeatureFlagIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;

    @Test
    void noInstanceControlBeanExistsAnywhereInTheContext() {
        assertEquals(0, context.getBeanNamesForType(InstanceRegistryService.class).length);
        assertEquals(0, context.getBeanNamesForType(RunGroupService.class).length);
        assertEquals(0, context.getBeanNamesForType(RunGroupDispatcher.class).length);
        assertEquals(0, context.getBeanNamesForType(RunGroupPoller.class).length);
        // Including the lease-recovery sweep. It outlives the *execution* switch on purpose, but
        // the parent switch takes the whole surface with it: with nothing to settle there is
        // nothing to sweep.
        assertEquals(0, context.getBeanNamesForType(RunGroupRecoveryService.class).length);
        assertEquals(0, context.getBeanNamesForType(DocumentManagerRunService.class).length);
        assertEquals(0, context.getBeanNamesForType(InstancePromotionService.class).length);
        assertEquals(0, context.getBeanNamesForType(InstanceModelCatalogService.class).length);
        assertEquals(0, context.getBeanNamesForType(CorpusSnapshotService.class).length);
        // Nothing can call the Document Engine, so nothing holds a client to it.
        assertEquals(0, context.getBeanNamesForType(DocumentEngineClient.class).length);
    }

    @Test
    void theStartupValidatorItselfIsAlwaysPresent() {
        // Unconditional on purpose: the bean that refuses incoherent flag combinations must
        // exist in every deployment, including the one where every flag is off.
        assertEquals(1, context.getBeanNamesForType(InstanceControlStartupValidator.class).length);
    }

    @Test
    void everyInstanceControlRouteIsUnmappedNotForbidden() throws Exception {
        // Admin paths: 404, not 401/403 — absent means no handler exists to even ask about
        // authorization, and an authenticated admin learns nothing about the flags.
        mvc.perform(get("/api/ai/admin/instances?brain=" + UUID.randomUUID())
                        .header("X-Admin-Api-Key", "flag-matrix-it-key"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/admin/instances/wizard-options")
                        .header("X-Admin-Api-Key", "flag-matrix-it-key"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/admin/lab/runs")
                        .header("X-Admin-Api-Key", "flag-matrix-it-key"))
                .andExpect(status().isNotFound());
        // Connector paths: 401, not 404 — the fail-closed boundary interceptor guards the whole
        // /api/connect/** prefix whether or not any route exists behind it, and an
        // unauthenticated caller gets exactly the same answer either way, so the response
        // reveals nothing about which flags this deployment runs.
        mvc.perform(post("/api/connect/v1/brains/any/instances/income/run-groups")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/connect/v1/brains/any/instance-run-groups/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theLegacyAdminSurfaceIsUntouched() throws Exception {
        mvc.perform(get("/api/ai/admin/brains")
                        .header("X-Admin-Api-Key", "flag-matrix-it-key"))
                .andExpect(status().isOk());
    }
}

/**
 * The one flag combination where settling and dispatching part company: the parent on, execution
 * off — the state the rollback runbook walks an operator into.
 *
 * <p><b>The sweep that settles in-flight work has to survive that switch.</b> It was originally
 * gated on the parent <em>and</em> {@code execution.enabled}, and its only caller was the
 * execution-gated poller, so turning dispatch off removed the one thing that could settle a member
 * that was {@code PROCESSING} at that moment. That member stayed {@code PROCESSING} indefinitely,
 * its {@code lab_spend_reservation} row stayed {@code RESERVED} against the brain's daily budget
 * where preflight counts it, and neither the retention sweep nor the stale-reservation sweep would
 * touch it, because both require a terminal run. A switch that stops new work must not strand work
 * already in flight.
 *
 * <p>So recovery is gated on the parent alone and schedules its own pass, while the dispatcher and
 * the poller — which claim, execute and call providers — stay behind both keys.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.instances.promotion-enabled=false",
        "ragbrain.instances.connector-enabled=false",
        "ragbrain.instances.execution.enabled=false",
        "ragbrain.lab.enabled=false",
        "ragbrain.rag.admin.api-key=flag-matrix-parent-only-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstanceControlParentOnlyFeatureFlagIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired ApplicationContext context;

    @Test
    void nothingDispatchesButTheSweepThatSettlesInFlightWorkStillRuns() {
        for (Class<?> type : List.<Class<?>>of(RunGroupDispatcher.class, RunGroupPoller.class)) {
            assertEquals(0, context.getBeanNamesForType(type).length,
                    type.getSimpleName() + " must not exist while execution is off");
        }
        assertTrue(context.getBeanNamesForType(RunGroupRecoveryService.class).length > 0,
                "the sweep that settles in-flight members must survive the dispatch switch");

        List<String> tasks = scheduledTasks();
        // Positive control first: the scheduler is running and this probe can see its tasks.
        assertFalse(tasks.isEmpty(), "the application's other scheduled jobs must still register");
        List<String> instanceTasks = tasks.stream()
                .filter(task -> task.contains("RunGroup")).toList();
        assertEquals(1, instanceTasks.size(),
                "exactly one instance task belongs here — the sweep, and nothing that dispatches: "
                        + instanceTasks);
        assertTrue(instanceTasks.get(0).contains("RunGroupRecoveryService")
                        && instanceTasks.get(0).contains("sweep"),
                "the one scheduled instance task must be the recovery sweep: " + instanceTasks);
    }

    /** Every task the scheduler actually registered, described by the method it will call. */
    private List<String> scheduledTasks() {
        return context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::toString)
                .toList();
    }
}

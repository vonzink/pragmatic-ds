package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.domain.ClarificationRule;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainProfileRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainProfileRepository profiles;

    @Autowired
    ClarificationRuleRepository rules;

    @Autowired
    BrainDocumentRepository documents;

    @Test
    void defaultProfileIsSeededForDefaultBrain() {
        BrainProfile profile = profiles.findByBrainId(TestBrains.DEFAULT_ID).orElseThrow();
        assertEquals(BrainMode.PUBLIC_SITE, profile.getMode());
        assertEquals(0.90, profile.getConfidenceTarget());
        assertFalse(profile.isPublicEnabled());
    }

    @Test
    void clarificationRulesAreReturnedByPriority() {
        ClarificationRule late = new ClarificationRule();
        late.setBrainId(TestBrains.DEFAULT_ID);
        late.setTopic("eligibility");
        late.setIntent("ELIGIBILITY");
        late.setRequiredFacts(List.of("occupancy"));
        late.setQuestion("Is this for a primary residence?");
        late.setPriority(20);
        late.setRequiredForPublic(true);
        late.setOptionalForGeneral(false);

        ClarificationRule early = new ClarificationRule();
        early.setBrainId(TestBrains.DEFAULT_ID);
        early.setTopic("eligibility");
        early.setIntent("ELIGIBILITY");
        early.setRequiredFacts(List.of("loanPurpose"));
        early.setQuestion("Is this purchase, refinance, or construction?");
        early.setPriority(10);
        early.setRequiredForPublic(true);
        early.setOptionalForGeneral(false);

        rules.save(late);
        rules.save(early);

        List<ClarificationRule> ordered =
                rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(TestBrains.DEFAULT_ID);
        assertEquals("loanPurpose", ordered.get(0).getRequiredFacts().get(0));
        assertEquals("occupancy", ordered.get(1).getRequiredFacts().get(0));
    }

    @Test
    void documentDefaultsArePublicApproved() {
        var doc = documents.findByBrainId(TestBrains.DEFAULT_ID).stream().findFirst();
        assertTrue(doc.isEmpty() || doc.get().getVisibility() == SourceVisibility.PUBLIC);
        assertTrue(doc.isEmpty() || doc.get().getTrustLevel() == SourceTrustLevel.APPROVED);
    }
}

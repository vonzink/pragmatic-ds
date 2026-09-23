package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /v1/packages/{id}/documents: the split projection review consumes — documents with their
 * pages and per-page classifications, plus the unassigned blank/duplicate pages with reasons.
 * Cross-tenant ids answer 404 exactly like nonexistent ones.
 */
class PackageDocumentsApiIT extends AbstractClassificationIT {

    @Autowired ParserPort parserPort;

    private void runStages(UUID packageId) {
        for (ProcessingStatus stage :
                List.of(ProcessingStatus.CLASSIFYING, ProcessingStatus.SPLITTING)) {
            parserPort.run(
                    new ParserPort.StageRequest(UUID.randomUUID(), packageId, stage, 1, "it-idem"));
        }
    }

    @Test
    void a_cleared_verdict_page_stays_accounted_for_in_both_read_models() throws Exception {
        // Audit C5. A reviewer who overrides a blank page's verdict (PageVerdictService sets
        // is_blank = false) leaves the page neither blank nor duplicate nor in any document —
        // and before this fix, in NO list either endpoint returned: an orphan the UI could only
        // paper over with session state a reload discarded. The read models must say CLEARED.
        UUID packageId = insertPackage("documents-cleared-it");
        List<UUID> pageIds = insertFixturePages(packageId, "combined_package");

        // BEFORE the split nothing is linked, and listing every page as CLEARED would misreport
        // an in-flight package as a tray full of reviewer work — only real verdicts show.
        mockMvc.perform(get("/v1/packages/{id}/documents", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(0))
                .andExpect(jsonPath("$.unassignedPages.length()").value(3))
                .andExpect(jsonPath("$.unassignedPages[0].reason").value("BLANK"));

        // MockMvc runs the filter chain on the test thread, and ContextClearingFilter unbinds
        // the tenant after every request — the GET above wiped the @BeforeEach binding the
        // stage run below depends on.
        TenantContext.set(ORG_DEV);
        runStages(packageId);
        jdbc.update("UPDATE page SET is_blank = false WHERE id = ?", pageIds.get(9));

        mockMvc.perform(get("/v1/packages/{id}/documents", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unassignedPages.length()").value(3))
                .andExpect(
                        jsonPath("$.unassignedPages[0].pageId").value(pageIds.get(9).toString()))
                .andExpect(jsonPath("$.unassignedPages[0].reason").value("CLEARED"))
                .andExpect(jsonPath("$.unassignedPages[1].reason").value("DUPLICATE"))
                .andExpect(jsonPath("$.unassignedPages[2].reason").value("DUPLICATE"));

        // The export tells the same story: a consumer's page accounting must not lose the page.
        mockMvc.perform(get("/v1/packages/{id}/export", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unassignedPages[0].reason").value("CLEARED"))
                .andExpect(jsonPath("$.unassignedPages[0].pageNumber").value(10));
    }

    @Test
    void the_combined_package_grouping_comes_back_with_pages_and_reasons() throws Exception {
        UUID packageId = insertPackage("documents-api-it");
        List<UUID> pageIds = insertFixturePages(packageId, "combined_package");
        runStages(packageId);

        mockMvc.perform(get("/v1/packages/{id}/documents", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(3))
                .andExpect(jsonPath("$.documents[0].ordinal").value(0))
                .andExpect(jsonPath("$.documents[0].documentTypeCode").value("PAYSTUB"))
                .andExpect(jsonPath("$.documents[0].reviewStatus").value("NOT_REVIEWED"))
                .andExpect(jsonPath("$.documents[0].classificationConfidence").value(1.0))
                // The package's first document did not have its boundary inferred from anything.
                .andExpect(jsonPath("$.documents[0].boundaryProvenance").value("PACKAGE_START"))
                .andExpect(jsonPath("$.documents[0].pages.length()").value(2))
                .andExpect(jsonPath("$.documents[0].pages[0].pageId").value(pageIds.get(0).toString()))
                .andExpect(jsonPath("$.documents[0].pages[0].packagePageIndex").value(0))
                .andExpect(jsonPath("$.documents[0].pages[0].classification.type").value("PAYSTUB"))
                // paystub@1.2.0 since V36's payroll-bureau supersession.
                .andExpect(
                        jsonPath("$.documents[0].pages[0].classification.rulePackVersion").value("1.2.0"))
                .andExpect(jsonPath("$.documents[1].documentTypeCode").value("BANK_STATEMENT"))
                // Cut because the classified type changed — real evidence, and the weakest of the
                // reasons the splitter can give. A reviewer triaging this package sees which of
                // its three boundaries were inferred rather than proven.
                .andExpect(jsonPath("$.documents[1].boundaryProvenance").value("TYPE_CHANGE"))
                .andExpect(jsonPath("$.documents[1].pages.length()").value(6))
                .andExpect(jsonPath("$.documents[2].documentTypeCode").value("W2"))
                .andExpect(jsonPath("$.documents[2].boundaryProvenance").value("TYPE_CHANGE"))
                // The eight letter pages classify UNKNOWN, and an untyped page continues the
                // open run rather than starting a document, so they are the W-2's pages 1-8 and
                // reachable from it. The per-page classification still reports each one honestly
                // as UNKNOWN — the reviewer sees exactly what the classifier said.
                .andExpect(jsonPath("$.documents[2].pages.length()").value(9))
                .andExpect(jsonPath("$.documents[2].pages[0].classification.type").value("W2"))
                .andExpect(jsonPath("$.documents[2].pages[1].packagePageIndex").value(12))
                .andExpect(jsonPath("$.documents[2].pages[1].classification.type").value("UNKNOWN"))
                // The untyped pages do not drag the document's classification confidence down:
                // a page with no type signal is not evidence against the type.
                .andExpect(jsonPath("$.documents[2].classificationConfidence").value(1.0))
                .andExpect(jsonPath("$.unassignedPages.length()").value(3))
                .andExpect(jsonPath("$.unassignedPages[0].pageId").value(pageIds.get(9).toString()))
                .andExpect(jsonPath("$.unassignedPages[0].packagePageIndex").value(9))
                .andExpect(jsonPath("$.unassignedPages[0].reason").value("BLANK"))
                .andExpect(jsonPath("$.unassignedPages[1].reason").value("DUPLICATE"))
                .andExpect(jsonPath("$.unassignedPages[2].reason").value("DUPLICATE"));
    }

    @Test
    void an_unknown_package_id_is_not_found() throws Exception {
        mockMvc.perform(get("/v1/packages/{id}/documents", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void another_orgs_package_is_indistinguishable_from_a_nonexistent_one() throws Exception {
        UUID foreignPackage = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                foreignPackage,
                ORG_OTHER,
                "foreign package with documents");

        MvcResult result =
                mockMvc.perform(get("/v1/packages/{id}/documents", foreignPackage))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("foreign package with documents");
    }
}

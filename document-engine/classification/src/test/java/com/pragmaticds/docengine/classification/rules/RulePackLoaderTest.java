package com.pragmaticds.docengine.classification.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.classification.domain.ClassificationRulePack;
import com.pragmaticds.docengine.classification.domain.DocumentType;
import com.pragmaticds.docengine.classification.repo.ClassificationRulePackRepository;
import com.pragmaticds.docengine.classification.repo.DocumentTypeRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pack loading and the shadowing rule: for each document_type_code, an org-scoped active pack
 * hides EVERY global pack of that type (the private mortgage packs replace the shipped generic
 * ones wholesale — they never merge); among the surviving scope's active packs, the highest
 * version wins. Parsed packs cache per org until {@link RulePackLoader#invalidate}.
 */
@ExtendWith(MockitoExtension.class)
class RulePackLoaderTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String PAYSTUB_DEF =
            """
            {"targetScore": 10, "anchors": [
              {"id": "pay-period", "kind": "literal", "pattern": "Pay Period", "weight": 3},
              {"id": "ytd", "kind": "regex", "pattern": "\\\\bYTD\\\\b", "weight": 1}]}
            """;

    /**
     * A pack whose header anchor declares that matching it STARTS A NEW DOCUMENT: the flag a
     * borrower's second Schedule E needs so the splitter stops merging two forms into one
     * four-page document. The second anchor deliberately omits the key — an ordinary anchor is
     * written exactly as it always was, and must load as {@code startsDocument = false}.
     */
    private static final String SCHEDULE_E_DEF =
            """
            {"targetScore": 10, "anchors": [
              {"id": "schedule-e-header", "kind": "literal", "pattern": "SCHEDULE E (Form 1040)",
               "weight": 5, "startsDocument": true},
              {"id": "supplemental-income", "kind": "literal",
               "pattern": "Supplemental Income and Loss", "weight": 4}]}
            """;

    @Mock private ClassificationRulePackRepository packs;
    @Mock private DocumentTypeRepository types;

    private RulePackLoader loader;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG);
        loader = new RulePackLoader(packs, types);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void typesActive(String... codes) {
        when(types.findActiveVisibleTo(ORG))
                .thenReturn(
                        java.util.Arrays.stream(codes)
                                .map(code -> new DocumentType(null, code, code, null, true))
                                .toList());
    }

    private static ClassificationRulePack pack(
            UUID orgId, String type, String version, String definition) {
        return new ClassificationRulePack(
                orgId, type, version, definition, new BigDecimal("0.6"), true);
    }

    @Test
    void parses_the_v1_pack_format_into_anchors_and_thresholds() {
        typesActive("PAYSTUB");
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(List.of(pack(null, "PAYSTUB", "1.0.0", PAYSTUB_DEF)));

        List<RulePack> loaded = loader.activePacksForCurrentOrg();

        assertThat(loaded).hasSize(1);
        RulePack paystub = loaded.get(0);
        assertThat(paystub.documentTypeCode()).isEqualTo("PAYSTUB");
        assertThat(paystub.version()).isEqualTo("1.0.0");
        assertThat(paystub.minConfidence()).isEqualTo(0.6);
        assertThat(paystub.targetScore()).isEqualTo(10.0);
        assertThat(paystub.anchors())
                .extracting(Anchor::id, Anchor::kind, Anchor::pattern, Anchor::weight)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "pay-period", AnchorKind.LITERAL, "Pay Period", 3.0),
                        org.assertj.core.groups.Tuple.tuple("ytd", AnchorKind.REGEX, "\\bYTD\\b", 1.0));
    }

    @Test
    void an_anchor_may_declare_that_matching_it_STARTS_a_new_document() {
        typesActive("SCHEDULE_E");
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(List.of(pack(null, "SCHEDULE_E", "1.0.0", SCHEDULE_E_DEF)));

        List<Anchor> anchors = loader.activePacksForCurrentOrg().get(0).anchors();

        assertThat(anchors)
                .extracting(Anchor::id, Anchor::startsDocument)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("schedule-e-header", true),
                        org.assertj.core.groups.Tuple.tuple("supplemental-income", false));
    }

    @Test
    void an_anchor_that_does_not_mention_the_flag_does_not_start_a_document() {
        // The key point of parsing it EXPLICITLY. JsonNode.path returns MissingNode for an
        // absent key and this loader validates no schema, so an unknown key in a pack
        // definition is silently ignored — which means a flag nobody reads is indistinguishable
        // from a flag nobody set. Every v1-format pack in the database predates this key and
        // must keep loading as ordinary anchors.
        typesActive("PAYSTUB");
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(List.of(pack(null, "PAYSTUB", "1.0.0", PAYSTUB_DEF)));

        assertThat(loader.activePacksForCurrentOrg().get(0).anchors())
                .extracting(Anchor::startsDocument)
                .containsExactly(false, false);
    }

    @Test
    void a_pack_may_declare_its_plausible_page_count_and_silence_means_none() {
        // Phase D: consumed only by the boundary-window planner. Absent — every pack authored
        // before the key existed — must load as NULL ("declares nothing"), never as zero.
        typesActive("PAYSTUB", "W2");
        String paystubWithMax =
                """
                {"targetScore": 10, "plausiblePageMax": 2, "anchors": [
                  {"id": "pay-period", "kind": "literal", "pattern": "Pay Period", "weight": 3}]}
                """;
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                pack(null, "PAYSTUB", "1.0.0", paystubWithMax),
                                pack(null, "W2", "1.0.0", PAYSTUB_DEF)));

        List<RulePack> loaded = loader.activePacksForCurrentOrg();

        assertThat(loaded)
                .extracting(RulePack::documentTypeCode, RulePack::plausiblePageMax)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("PAYSTUB", 2),
                        org.assertj.core.groups.Tuple.tuple("W2", null));
    }

    @Test
    void a_nonpositive_plausible_page_count_is_a_broken_pack_not_a_silent_zero() {
        typesActive("PAYSTUB");
        String broken =
                """
                {"targetScore": 10, "plausiblePageMax": 0, "anchors": [
                  {"id": "pay-period", "kind": "literal", "pattern": "Pay Period", "weight": 3}]}
                """;
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(List.of(pack(null, "PAYSTUB", "1.0.0", broken)));

        assertThatThrownBy(() -> loader.activePacksForCurrentOrg())
                .isInstanceOf(DomainException.class)
                .satisfies(
                        thrown ->
                                assertThat(((DomainException) thrown).code())
                                        .isEqualTo(ErrorCode.INTERNAL));
    }

    @Test
    void an_org_pack_shadows_every_global_pack_of_the_same_type() {
        typesActive("PAYSTUB", "W2");
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                pack(null, "PAYSTUB", "1.0.0", PAYSTUB_DEF),
                                pack(ORG, "PAYSTUB", "1.1.0", PAYSTUB_DEF),
                                pack(null, "W2", "1.0.0", PAYSTUB_DEF)));

        List<RulePack> loaded = loader.activePacksForCurrentOrg();

        assertThat(loaded)
                .extracting(RulePack::documentTypeCode, RulePack::version)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("PAYSTUB", "1.1.0"),
                        org.assertj.core.groups.Tuple.tuple("W2", "1.0.0"));
    }

    @Test
    void the_highest_version_wins_within_one_scope() {
        typesActive("PAYSTUB");
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                pack(null, "PAYSTUB", "1.2.0", PAYSTUB_DEF),
                                pack(null, "PAYSTUB", "1.10.0", PAYSTUB_DEF)));

        assertThat(loader.activePacksForCurrentOrg())
                .extracting(RulePack::version)
                // numeric segment compare: 1.10.0 > 1.2.0 (string compare would invert it)
                .containsExactly("1.10.0");
    }

    @Test
    void a_pack_for_an_inactive_or_unknown_type_is_skipped() {
        typesActive("PAYSTUB");
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                pack(null, "PAYSTUB", "1.0.0", PAYSTUB_DEF),
                                pack(null, "RETIRED_TYPE", "1.0.0", PAYSTUB_DEF)));

        assertThat(loader.activePacksForCurrentOrg())
                .extracting(RulePack::documentTypeCode)
                .containsExactly("PAYSTUB");
    }

    @Test
    void packs_cache_per_org_until_invalidated() {
        typesActive("PAYSTUB");
        when(packs.findActiveVisibleTo(ORG))
                .thenReturn(List.of(pack(null, "PAYSTUB", "1.0.0", PAYSTUB_DEF)));

        loader.activePacksForCurrentOrg();
        loader.activePacksForCurrentOrg();
        verify(packs, times(1)).findActiveVisibleTo(any());

        loader.invalidate(ORG);
        loader.activePacksForCurrentOrg();
        verify(packs, times(2)).findActiveVisibleTo(any());
    }

    @Test
    void no_loadable_pack_at_all_is_NO_RULE_PACK() {
        typesActive("PAYSTUB");
        when(packs.findActiveVisibleTo(ORG)).thenReturn(List.of());

        assertThatThrownBy(() -> loader.activePacksForCurrentOrg())
                .isInstanceOf(DomainException.class)
                .satisfies(
                        e -> assertThat(((DomainException) e).code()).isEqualTo(ErrorCode.NO_RULE_PACK));
    }

    @Test
    void an_unparseable_definition_is_INTERNAL_and_names_only_the_pack_id() {
        typesActive("PAYSTUB");
        ClassificationRulePack broken = pack(null, "PAYSTUB", "1.0.0", "{\"anchors\": \"nope\"");
        when(packs.findActiveVisibleTo(ORG)).thenReturn(List.of(broken));

        assertThatThrownBy(() -> loader.activePacksForCurrentOrg())
                .isInstanceOf(DomainException.class)
                .satisfies(
                        e -> {
                            DomainException domain = (DomainException) e;
                            assertThat(domain.code()).isEqualTo(ErrorCode.INTERNAL);
                            // The pack id is the only parameter — never definition content.
                            assertThat(domain.params().keySet()).containsExactly("rulePackId");
                        });
    }
}

import { describe, expect, it } from "vitest";
import { LabEnvelopeResponse, LabField } from "../types";
import { buildEnvelopeView, fieldValue, groupLabel, pageLabel } from "./parseEnvelope";

/**
 * The canary from the plan. It has more significant digits than an IEEE-754 double can hold, so
 * any path that turns it into a JavaScript number loses digits. The backend sends it as a JSON
 * STRING (BigDecimal.toPlainString()), and everything here keeps it a string.
 */
const DECIMAL_CANARY = "12345678901234567890.123456789";

const PAGE_A = "11111111-1111-4111-8111-111111111111";
const PAGE_B = "22222222-2222-4222-8222-222222222222";
const DOC_A = "33333333-3333-4333-8333-333333333333";

function field(overrides: Partial<LabField> = {}): LabField {
  return {
    name: "grossPay",
    groupKey: null,
    status: "FOUND",
    dataType: "MONEY",
    displayedText: "1,234.56",
    rawValue: "1,234.56",
    normalizedText: null,
    normalizedNumber: "1234.56",
    normalizedDate: null,
    confidence: 0.97,
    method: "TEXT_LAYER",
    extractorVersion: "v3",
    validationStatus: "VALID",
    sensitive: false,
    evidence: [],
    reviewState: "UNREVIEWED_SOURCE",
    ...overrides,
  };
}

function envelope(fields: LabField[]): LabEnvelopeResponse {
  return {
    registrationId: "44444444-4444-4444-8444-444444444444",
    packageId: "55555555-5555-4555-8555-555555555555",
    revision: 1,
    parseGeneration: 1,
    processingJobId: "66666666-6666-4666-8666-666666666666",
    envelopeVersion: "1.0.0",
    canonicalizationVersion: "DOCENGINE-C14N-1",
    envelopeSha256: "a".repeat(64),
    envelopeSizeBytes: 2048,
    sourceSetSha256: "b".repeat(64),
    reuseEligibility: "ELIGIBLE",
    compatible: true,
    rejection: null,
    warnings: [],
    pages: [
      {
        id: PAGE_A, packagePageIndex: 0, sourcePageIndex: 0, widthPt: 612, heightPt: 792,
        rotation: 0, textLayer: "DIGITAL", blank: false, duplicate: false,
        documentTypeCode: "PAYSTUB", classificationConfidence: 0.99, classificationMethod: "RULES",
      },
      {
        id: PAGE_B, packagePageIndex: 1, sourcePageIndex: 1, widthPt: 612, heightPt: 792,
        rotation: 0, textLayer: "OCR", blank: false, duplicate: false,
        documentTypeCode: null, classificationConfidence: null, classificationMethod: null,
      },
    ],
    documents: [
      { id: DOC_A, documentTypeCode: "PAYSTUB", ordinal: 0, pageIds: [PAGE_A], fields },
    ],
    unassignedPageIds: [PAGE_B],
    prototype: { code: "PROTOTYPE_LIVE_DEPENDENCIES", liveDependencies: ["CORPUS_CONTENTS"] },
  };
}

describe("fieldValue", () => {
  it("keeps a normalized decimal byte-for-byte, with no IEEE-754 rounding anywhere", () => {
    // Parse from wire text, exactly as the browser would, to prove the contract end to end.
    const wire = JSON.parse(`{"normalizedNumber":"${DECIMAL_CANARY}"}`) as { normalizedNumber: string };
    const value = fieldValue(field({ normalizedNumber: wire.normalizedNumber }));

    expect(value.kind).toBe("NORMALIZED_NUMBER");
    expect(value.text).toBe(DECIMAL_CANARY);
    // The failure this guards: Number(canary).toString() drops digits.
    expect(value.text).not.toBe(String(Number(DECIMAL_CANARY)));
  });

  it("prefers normalized date, then normalized text, then displayed, then raw", () => {
    expect(fieldValue(field({ normalizedNumber: null, normalizedDate: "2026-03-31" })))
      .toEqual({ text: "2026-03-31", kind: "NORMALIZED_DATE" });
    expect(fieldValue(field({ normalizedNumber: null, normalizedText: "ACME CORP" })))
      .toEqual({ text: "ACME CORP", kind: "NORMALIZED_TEXT" });
    expect(fieldValue(field({ normalizedNumber: null, displayedText: "1,234.56" })))
      .toEqual({ text: "1,234.56", kind: "DISPLAYED" });
    expect(fieldValue(field({ normalizedNumber: null, displayedText: null, rawValue: "1 234,56" })))
      .toEqual({ text: "1 234,56", kind: "RAW" });
  });

  it("renders an explicit missing marker instead of a blank cell", () => {
    const missing = fieldValue(field({
      status: "MISSING", method: "NONE", confidence: 0,
      displayedText: null, rawValue: null, normalizedNumber: null,
    }));
    expect(missing).toEqual({ text: "missing", kind: "MISSING" });
    expect(missing.text.trim()).not.toBe("");
  });

  it("never paints a sensitive value, even when the wire carries one", () => {
    const value = fieldValue(field({
      sensitive: true,
      normalizedNumber: null,
      normalizedText: "123456789",
      displayedText: "123-45-6789",
      rawValue: "123-45-6789",
    }));
    expect(value).toEqual({ text: "redacted (sensitive)", kind: "REDACTED" });
  });

  it("still says missing for a sensitive field the engine did not find", () => {
    expect(fieldValue(field({ sensitive: true, status: "MISSING", normalizedNumber: null })).kind)
      .toBe("MISSING");
  });

  it("says absent rather than blank when a FOUND field carries no value at all", () => {
    expect(fieldValue(field({ displayedText: null, rawValue: null, normalizedNumber: null })))
      .toEqual({ text: "no value", kind: "ABSENT" });
  });

  it("shows a reviewer rejection instead of the missing marker", () => {
    expect(fieldValue(field({ status: "MISSING", reviewState: "REJECTED", normalizedNumber: null })))
      .toEqual({ text: "rejected by reviewer", kind: "REJECTED" });
  });

  it("shows a reviewer rejection even when status still says FOUND", () => {
    // reviewState alone must gate REJECTED: a rejected value can never be painted, regardless of
    // what status the occurrence otherwise carries.
    expect(fieldValue(field({ status: "FOUND", reviewState: "REJECTED" })))
      .toEqual({ text: "rejected by reviewer", kind: "REJECTED" });
  });
});

describe("groupLabel", () => {
  it("uses the engine's row letter when the envelope carries one", () => {
    expect(groupLabel(field({ groupKey: "B" })))
      .toEqual({ label: "B", certainty: "KEYED" });
  });

  it("calls a FOUND field with no group key ungrouped", () => {
    expect(groupLabel(field({ groupKey: null, status: "FOUND" })))
      .toEqual({ label: "ungrouped", certainty: "UNGROUPED" });
  });

  it("refuses to guess for a MISSING field with no group key", () => {
    // Envelope 1.0.0 has no grouping-kind member, so a null groupKey on a MISSING field cannot
    // be told apart from an ungrouped one. Say unknown rather than pick a side.
    expect(groupLabel(field({ groupKey: null, status: "MISSING" })))
      .toEqual({ label: "unknown", certainty: "UNKNOWN" });
  });
});

describe("pageLabel", () => {
  it("presents the zero-based package page index as a one-based page number", () => {
    expect(pageLabel(PAGE_B, envelope([]).pages)).toBe("p. 2");
  });

  it("names an unknown page by nothing more than its identifier prefix", () => {
    expect(pageLabel("99999999-9999-4999-8999-999999999999", envelope([]).pages))
      .toBe("page 99999999");
  });
});

describe("buildEnvelopeView", () => {
  it("keeps missing fields visible and counts them", () => {
    const view = buildEnvelopeView(envelope([
      field({ name: "grossPay" }),
      field({ name: "ytdGross", status: "MISSING", method: "NONE", confidence: 0,
              displayedText: null, rawValue: null, normalizedNumber: null }),
    ]));

    expect(view.fieldCount).toBe(2);
    expect(view.foundCount).toBe(1);
    expect(view.missingCount).toBe(1);
    const names = view.documents[0].groups.flatMap((g) => g.rows.map((r) => r.field.name));
    expect(names).toEqual(["grossPay", "ytdGross"]);
  });

  it("preserves the envelope's field order and first-appearance group order", () => {
    const view = buildEnvelopeView(envelope([
      field({ name: "employer", groupKey: null }),
      field({ name: "amount", groupKey: "A" }),
      field({ name: "amount", groupKey: "B" }),
      field({ name: "rate", groupKey: "A" }),
    ]));

    expect(view.documents[0].groups.map((g) => g.label)).toEqual(["ungrouped", "A", "B"]);
    expect(view.documents[0].groups[1].rows.map((r) => r.field.name)).toEqual(["amount", "rate"]);
  });

  it("flags grouping as ambiguous only when a missing field has no group key", () => {
    expect(buildEnvelopeView(envelope([field({ groupKey: "A" })])).groupingAmbiguous).toBe(false);
    expect(buildEnvelopeView(envelope([field({ groupKey: null })])).groupingAmbiguous).toBe(false);
    expect(buildEnvelopeView(envelope([
      field({ status: "MISSING", groupKey: null, normalizedNumber: null }),
    ])).groupingAmbiguous).toBe(true);
  });

  it("labels evidence by page and keeps its box coordinates", () => {
    const view = buildEnvelopeView(envelope([
      field({
        evidence: [{ pageId: PAGE_A, role: "VALUE", ordinal: 0,
                     box: { x: 72, y: 700, width: 120, height: 12 } }],
      }),
    ]));

    const evidence = view.documents[0].groups[0].rows[0].evidence;
    expect(evidence).toEqual([{
      pageLabel: "p. 1", role: "VALUE", ordinal: 0,
      box: { x: 72, y: 700, width: 120, height: 12 },
    }]);
  });

  it("reports page membership and pages the engine assigned to no document", () => {
    const view = buildEnvelopeView(envelope([field()]));
    expect(view.documents[0].pageLabels).toEqual(["p. 1"]);
    expect(view.unassignedPageLabels).toEqual(["p. 2"]);
    expect(view.pageCount).toBe(2);
  });
});

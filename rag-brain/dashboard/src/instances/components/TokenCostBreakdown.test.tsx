import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

/**
 * One rule, tested at the only place that implements it: a missing number is never a zero.
 *
 * The distinction is not cosmetic. `Unavailable` says the provider reported no figure for that
 * category; `0` says it reported none consumed. An operator reconciling an invoice against this
 * screen acts differently on each, and a component that renders them alike turns a missing
 * measurement into a measured absence.
 */

import TokenCostBreakdown, { CostValue, EstimatedRange, UsageValue } from "./TokenCostBreakdown";

const ESTIMATE = {
  memberIndex: 0,
  instanceSlug: "income",
  releaseId: "r",
  registrationId: "g",
  corpusSnapshotId: "s",
  provider: "anthropic",
  model: "claude-opus-5",
  pricingVersionId: "p",
  inputTokensMin: 1000,
  inputTokensMax: 1400,
  outputTokensMin: 200,
  outputTokensMax: 400,
  costUsdMin: 0.01,
  costUsdMax: 0.03,
  estimateQuality: "EXACT",
} as never;

function member(over: Partial<Record<string, unknown>> = {}) {
  return {
    memberIndex: 0,
    runId: "run",
    instanceSlug: "income",
    releaseId: "r",
    registrationId: "g",
    corpusSnapshotId: "s",
    status: "SUCCEEDED",
    failureCode: null,
    provider: "anthropic",
    model: "claude-opus-5",
    pricingVersionId: "p",
    expectedInputMin: 1000,
    expectedInputMax: 1400,
    expectedOutputMin: 200,
    expectedOutputMax: 400,
    expectedCostUsdMin: 0.01,
    expectedCostUsdMax: 0.03,
    estimateQuality: "EXACT",
    actualInputTokens: 1200,
    actualCachedTokens: 400,
    actualOutputTokens: 300,
    actualTotalTokens: 1500,
    actualCostUsd: 0.02,
    usageQuality: "REPORTED",
    createdAt: "2026-08-20T10:00:00Z",
    terminalAt: "2026-08-20T10:01:00Z",
    result: null,
    ...over,
  } as never;
}

function factOf(label: string) {
  return screen.getByText(label).closest("div")!.textContent!;
}

describe("UsageValue", () => {
  it("says unavailable rather than zero when nothing was reported", () => {
    render(<UsageValue value={null} quality="UNAVAILABLE" />);
    expect(screen.getByText("Unavailable")).toBeTruthy();
    expect(document.body.textContent).not.toContain("0");
  });

  it("distinguishes a run still reporting from one that never will", () => {
    const { unmount } = render(<UsageValue value={null} quality="PENDING" />);
    expect(screen.getByText("Pending")).toBeTruthy();
    unmount();

    render(<UsageValue value={null} quality="UNAVAILABLE" />);
    expect(screen.getByText("Unavailable")).toBeTruthy();
  });

  it("keeps a genuine zero, which is a measurement like any other", () => {
    render(<UsageValue value={0} quality="REPORTED" />);
    // The whole point of the null check is that this case stays reachable and distinct.
    expect(document.body.textContent).toBe("0");
  });

  it("marks a derived figure so it cannot pass for a reported one", () => {
    render(<UsageValue value={1234} quality="INFERRED" />);
    expect(document.body.textContent).toContain("1,234");
    expect(screen.getByText("Inferred")).toBeTruthy();
  });
});

describe("CostValue", () => {
  it("says unavailable rather than $0 when no cost was measured", () => {
    render(<CostValue value={null} quality="UNAVAILABLE" />);
    expect(screen.getByText("Unavailable")).toBeTruthy();
    expect(document.body.textContent).not.toContain("$");
  });
});

describe("EstimatedRange", () => {
  it("cannot render a bound without saying it is one", () => {
    render(<EstimatedRange min={0.01} max={0.03} money />);
    expect(document.body.textContent).toContain("$0.01 – $0.03");
    expect(screen.getByText("Estimated")).toBeTruthy();
  });
});

describe("TokenCostBreakdown", () => {
  it("prices a member that has not run from its estimate alone", () => {
    render(<TokenCostBreakdown estimate={ESTIMATE} member={null} />);

    expect(factOf("Expected input")).toContain("1,000 – 1,400");
    // Nothing has run, so there is nothing to report — and no actual columns pretending otherwise.
    expect(screen.queryByText("Actual input")).toBeNull();
    expect(screen.queryByText("Usage quality")).toBeNull();
  });

  it("keeps the expected columns after the run finishes", () => {
    render(<TokenCostBreakdown estimate={null} member={member()} />);

    // Dropping these at completion would hide the one result worth looking for: an actual that
    // landed outside the range the run was approved against.
    expect(factOf("Expected cost")).toContain("$0.01 – $0.03");
    expect(factOf("Actual est. cost")).toContain("$0.02");
    expect(factOf("Actual total")).toContain("1,500");
  });

  it("prices a finished member from what it was submitted with, not from today's estimate", () => {
    render(<TokenCostBreakdown
      estimate={{ ...(ESTIMATE as object), costUsdMin: 9, costUsdMax: 99 } as never}
      member={member()} />);

    // Re-pricing a finished run against a live estimate would quietly rewrite what it was
    // approved at, which is the number an invoice will be checked against.
    expect(factOf("Expected cost")).toContain("$0.01 – $0.03");
    expect(factOf("Expected cost")).not.toContain("$99");
  });

  it("shows an unreported category as unavailable beside categories that were reported", () => {
    render(<TokenCostBreakdown estimate={null} member={member({
      actualCachedTokens: null, usageQuality: "REPORTED",
    })} />);

    expect(factOf("Cached input")).toContain("Unavailable");
    expect(factOf("Actual input")).toContain("1,200");
  });
});

import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * What the flag decides, on both sides of it.
 *
 * The off case is the one that matters most and is the easiest to lose: with
 * `VITE_INSTANCE_CONTROL_ENABLED` absent the dashboard must carry no instance screens — same
 * routes, same catch-all landing on Corpus. Not "hidden behind a condition" but absent, so an
 * unknown path cannot fall into an instance screen.
 *
 * The on case asserts the approved primary order rather than merely that the links exist. Order
 * is the product decision here: the nav is grouped Operate / Knowledge / Configure, and with the
 * flag on Operate leads with the instance — the instance is the thing you work with, and the
 * brain a detail of provenance.
 */

/**
 * The screens are stubbed deliberately.
 *
 * This file tests routing and navigation, and a screen that cannot render its own data would fail
 * it for an unrelated reason — the first draft did exactly that, with Corpus throwing on a canned
 * fetch response and React unmounting the tree, so the nav assertions failed while routing was
 * fine. Stubs keep the failure surface to the thing under test, and keep this file from breaking
 * every time a screen changes what it fetches.
 */
vi.mock("./screens/Corpus", () => ({ default: () => <div>Corpus screen</div> }));
vi.mock("./screens/Brains", () => ({ default: () => <div>Brains screen</div> }));
vi.mock("./screens/Connect", () => ({ default: () => <div>Connect screen</div> }));
vi.mock("./screens/Connectors", () => ({ default: () => <div>Connectors screen</div> }));
vi.mock("./screens/Tools", () => ({ default: () => <div>Tools screen</div> }));
vi.mock("./screens/Settings", () => ({ default: () => <div>Settings screen</div> }));
vi.mock("./screens/Rules", () => ({ default: () => <div>Rules screen</div> }));
vi.mock("./screens/Vocabulary", () => ({ default: () => <div>Vocabulary screen</div> }));
vi.mock("./screens/SourceLinks", () => ({ default: () => <div>SourceLinks screen</div> }));
vi.mock("./screens/PageGuides", () => ({ default: () => <div>PageGuides screen</div> }));
vi.mock("./screens/TestConsole", () => ({ default: () => <div>TestConsole screen</div> }));
vi.mock("./screens/Audit", () => ({ default: () => <div>Audit screen</div> }));
vi.mock("./screens/Personality", () => ({ default: () => <div>Personality screen</div> }));
vi.mock("./screens/IncomeLab", () => ({
  default: () => <div>Income lab screen</div>,
  labEnabled: () => false,
}));

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    api: { ...actual.api, get: vi.fn(async () => stats) },
    adminKey: { get: () => "k", set: vi.fn(), clear: vi.fn() },
  };
});

const stats = {
  brain: { id: "brain-1", companyName: "Generic", slug: "generic" },
  corpus: { activeDocuments: 1, totalDocuments: 1, chunks: 3 },
};

async function renderAt(path: string) {
  window.location.hash = `#${path}`;
  const App = (await import("./App")).default;
  return render(<App />);
}

beforeEach(() => {
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
  window.location.hash = "";
});

describe("with instance control off", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "");
  });

  it("shows no instance-control navigation", async () => {
    await renderAt("/corpus");

    await waitFor(() => expect(screen.getByRole("link", { name: "Corpus library" })).toBeTruthy());
    expect(screen.queryByText("Instances")).toBeNull();
    expect(screen.queryByText("Run queue")).toBeNull();
    // The legacy settings screen carries the design's name when it is the only model surface.
    expect(screen.getByRole("link", { name: "Models & providers" })).toBeTruthy();
  });

  it("still sends an unknown path to Corpus", async () => {
    await renderAt("/nothing-here");

    // The catch-all is load-bearing: it is what an old bookmark and a stale link both hit.
    await waitFor(() => expect(window.location.hash).toContain("/corpus"));
  });

  it("does not route the instance paths at all", async () => {
    await renderAt("/instances");

    await waitFor(() => expect(window.location.hash).toContain("/corpus"));
  });
});

describe("with instance control on", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "true");
  });

  it("leads with the instance, not the brain", async () => {
    await renderAt("/instances");

    // Scoped to the nav: the active screen also has "Instances" as its heading, and asserting
    // over the whole document would match that too and say nothing about ordering. The links
    // carry aria-labels because the visible text also holds an icon glyph.
    const nav = await waitFor(() => screen.getByRole("navigation"));
    const primary = within(nav).getAllByRole("link").map((a) => a.getAttribute("aria-label"));
    expect(primary).toEqual([
      "Overview",
      "Instances",
      "Run queue",
      "Test console",
      "Corpus library",
      "Rules & guardrails",
      "Analyzer prompts",
      "Source links",
      "Vocabulary",
      "Page guides",
      "Models & providers",
      "Settings",
      "Connect",
      "Connectors",
      "Tools",
      "Personality",
      "Brains",
      "Audit log",
    ]);
  });

  it("keeps every screen visible in its group rather than demoting any under a disclosure", async () => {
    await renderAt("/instances");

    await waitFor(() => expect(screen.getByText("Operate")).toBeTruthy());
    expect(screen.getByText("Knowledge")).toBeTruthy();
    expect(screen.getByText("Configure")).toBeTruthy();
    expect(screen.queryByText("More")).toBeNull();
    // Not removed — Tools, Rules and the rest are still how the brain is configured.
    expect(screen.getByRole("link", { name: "Tools" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Test console" })).toBeTruthy();
  });

  it("sends an unknown path to Instances instead of Corpus", async () => {
    await renderAt("/nothing-here");

    await waitFor(() => expect(window.location.hash).toContain("/instances"));
  });

  it.each([
    "/instances",
    "/instances/new",
    "/instance-runs",
    "/instance-runs/new",
    "/instance-models",
  ])("routes %s without falling through to the catch-all", async (path) => {
    await renderAt(path);

    await waitFor(() => expect(window.location.hash).toContain(path));
  });

  it("routes a workspace path from its own segments, with no sidebar brain involved", async () => {
    // The path carries brain and instance. That is the whole point: the workspace does not read
    // the sidebar's ?brain= to know what it is showing.
    //
    // An unrecognised tab resolves to the workbench rather than falling through to the top-level
    // catch-all, so what this asserts is that the brain and slug survive — not that any arbitrary
    // sub-path is preserved verbatim, which was only true while the workspace was a placeholder.
    await renderAt("/instances/11111111-1111-4111-8111-111111111111/income/not-a-tab");

    await waitFor(() => {
      expect(window.location.hash).toContain("11111111-1111-4111-8111-111111111111");
      expect(window.location.hash).toContain("/income/");
    });
  });

  it("leaves the existing direct routes working", async () => {
    await renderAt("/brains");

    await waitFor(() => expect(window.location.hash).toContain("/brains"));
  });
});

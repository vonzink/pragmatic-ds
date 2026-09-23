import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The two build flags, in all four combinations.
 *
 * `App.instances.test.tsx` proves what the instance flag does with the Income Lab pinned off;
 * this file proves the flags are independent switches: turning either on never reveals, hides, or
 * reroutes the other's surface. That independence is a rollout property — the plan enables the
 * instance dashboard while the Lab prototype stays wherever it already is — and it is also the
 * regression that would be easiest to introduce silently, because both features live behind the
 * same nav component.
 *
 * Screens are stubbed: this file tests flag-driven routing, and a screen failing on its own
 * fetches would fail these assertions for an unrelated reason.
 */
vi.mock("./screens/InstanceLanding", () => ({
  default: () => <div>Instances landing screen</div>,
}));
vi.mock("../screens/Corpus", () => ({ default: () => <div>Corpus screen</div> }));
vi.mock("../screens/Brains", () => ({ default: () => <div>Brains screen</div> }));
vi.mock("../screens/Connect", () => ({ default: () => <div>Connect screen</div> }));
vi.mock("../screens/Connectors", () => ({ default: () => <div>Connectors screen</div> }));
vi.mock("../screens/Tools", () => ({ default: () => <div>Tools screen</div> }));
vi.mock("../screens/Settings", () => ({ default: () => <div>Settings screen</div> }));
vi.mock("../screens/Rules", () => ({ default: () => <div>Rules screen</div> }));
vi.mock("../screens/Vocabulary", () => ({ default: () => <div>Vocabulary screen</div> }));
vi.mock("../screens/SourceLinks", () => ({ default: () => <div>SourceLinks screen</div> }));
vi.mock("../screens/PageGuides", () => ({ default: () => <div>PageGuides screen</div> }));
vi.mock("../screens/TestConsole", () => ({ default: () => <div>TestConsole screen</div> }));
vi.mock("../screens/Audit", () => ({ default: () => <div>Audit screen</div> }));
vi.mock("../screens/Personality", () => ({ default: () => <div>Personality screen</div> }));
// The Income Lab's default export is stubbed for rendering, but `labEnabled` stays the real
// function so `VITE_FOLDER_AI_LAB_ENABLED` actually drives it — that is the flag under test.
vi.mock("../screens/IncomeLab", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../screens/IncomeLab")>();
  return { ...actual, default: () => <div>Income lab screen</div> };
});

vi.mock("../api", async () => {
  const actual = await vi.importActual<typeof import("../api")>("../api");
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
  const App = (await import("../App")).default;
  return render(<App />);
}

beforeEach(() => {
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
  window.location.hash = "";
});

describe("both flags off", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "");
    vi.stubEnv("VITE_FOLDER_AI_LAB_ENABLED", "");
  });

  it("shows neither surface and lands unknown paths on Corpus", async () => {
    await renderAt("/nowhere");

    await waitFor(() => expect(window.location.hash).toContain("/corpus"));
    expect(screen.queryByText("Instances")).toBeNull();
    expect(screen.queryByText("Income lab")).toBeNull();
  });
});

describe("lab flag on, instance flag off", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "");
    vi.stubEnv("VITE_FOLDER_AI_LAB_ENABLED", "true");
  });

  it("routes the Income Lab and nothing of the instance surface", async () => {
    await renderAt("/lab/income");

    await waitFor(() => expect(screen.getByText("Income lab screen")).toBeTruthy());
    expect(screen.queryByText("Instances")).toBeNull();
  });

  it("keeps the pre-instance catch-all: unknown paths land on Corpus", async () => {
    await renderAt("/instances");

    await waitFor(() => expect(window.location.hash).toContain("/corpus"));
  });
});

describe("instance flag on, lab flag off", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "true");
    vi.stubEnv("VITE_FOLDER_AI_LAB_ENABLED", "");
  });

  it("routes the instance surface and no Income Lab", async () => {
    await renderAt("/instances");

    await waitFor(() =>
      expect(screen.getByText("Instances landing screen")).toBeTruthy(),
    );
    expect(screen.queryByText("Income lab")).toBeNull();
  });

  it("sends the Income Lab path to the instance catch-all instead of rendering it", async () => {
    await renderAt("/lab/income");

    await waitFor(() => expect(window.location.hash).toContain("/instances"));
    expect(screen.queryByText("Income lab screen")).toBeNull();
  });
});

describe("both flags on", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_INSTANCE_CONTROL_ENABLED", "true");
    vi.stubEnv("VITE_FOLDER_AI_LAB_ENABLED", "true");
  });

  it("routes both surfaces without either rerouting the other", async () => {
    await renderAt("/instances");
    await waitFor(() =>
      expect(screen.getByText("Instances landing screen")).toBeTruthy(),
    );

    await renderAt("/lab/income");
    await waitFor(() => expect(screen.getByText("Income lab screen")).toBeTruthy());
  });
});

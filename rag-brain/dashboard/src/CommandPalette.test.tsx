import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The ⌘K palette: opened by hotkey or the sidebar trigger, filtered client-side.
 * The invariants worth pinning are about the ?brain= selector — a jump must never
 * silently reset the tenant, and switching the tenant must never change the screen.
 */

vi.mock("./screens/Corpus", () => ({ default: () => <div>Corpus screen</div> }));
vi.mock("./screens/Overview", () => ({ default: () => <div>Overview screen</div> }));
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

const brain = {
  id: "brain-1",
  slug: "mortgage",
  displayName: "Example Mortgage",
  packRef: null,
  sourceType: null,
  s3Bucket: null,
  s3Prefix: null,
  s3Region: null,
  localPath: null,
  answerProvider: null,
  answerModel: null,
  utilityProvider: null,
  utilityModel: null,
  isDefault: true,
  isActive: true,
  learningEnabled: true,
  dailyCostBudgetUsd: null,
};
const otherBrain = { ...brain, id: "brain-2", slug: "generic", displayName: "Generic Brain", isDefault: false };

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    api: { ...actual.api, get: vi.fn(async () => stats) },
    adminKey: { get: () => "k", set: vi.fn(), clear: vi.fn() },
    brainsApi: { ...actual.brainsApi, list: vi.fn(async () => [brain, otherBrain]) },
  };
});

const stats = {
  brain: { id: "brain-1", companyName: "Generic", slug: "mortgage" },
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

describe("command palette", () => {
  it("opens on the hotkey, filters, and navigates preserving the brain", async () => {
    await renderAt("/corpus?brain=mortgage");
    await waitFor(() => expect(screen.getByRole("navigation")).toBeTruthy());

    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const dialog = await screen.findByRole("dialog", { name: "Jump to" });

    await userEvent.type(within(dialog).getByRole("textbox"), "vocab");
    await userEvent.keyboard("{Enter}");

    await waitFor(() => expect(window.location.hash).toBe("#/vocabulary?brain=mortgage"));
    expect(screen.queryByRole("dialog", { name: "Jump to" })).toBeNull();
  });

  it("opens from the sidebar trigger and closes on Escape without navigating", async () => {
    await renderAt("/corpus");
    await waitFor(() => expect(screen.getByRole("navigation")).toBeTruthy());

    await userEvent.click(screen.getByRole("button", { name: /Jump to/ }));
    await screen.findByRole("dialog", { name: "Jump to" });

    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Jump to" })).toBeNull());
    expect(window.location.hash).toBe("#/corpus");
  });

  it("switches the brain in place, and never offers the brain already selected", async () => {
    await renderAt("/corpus?brain=mortgage");
    await waitFor(() => expect(screen.getByRole("navigation")).toBeTruthy());

    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const dialog = await screen.findByRole("dialog", { name: "Jump to" });

    // The brains list loads lazily; the switch entry for the other brain appears.
    const option = await within(dialog).findByText("Switch to Generic Brain");
    expect(within(dialog).queryByText("Switch to Example Mortgage")).toBeNull();

    await userEvent.click(option);
    // Same screen, different tenant.
    await waitFor(() => expect(window.location.hash).toBe("#/corpus?brain=generic"));
  });

  it("supports arrow-key selection", async () => {
    await renderAt("/corpus");
    await waitFor(() => expect(screen.getByRole("navigation")).toBeTruthy());

    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const dialog = await screen.findByRole("dialog", { name: "Jump to" });
    await within(dialog).findByText("Overview");

    // First entry (Overview) is selected; one step down lands on the next one.
    await userEvent.keyboard("{ArrowDown}{Enter}");
    await waitFor(() => expect(window.location.hash).toBe("#/console"));
  });
});

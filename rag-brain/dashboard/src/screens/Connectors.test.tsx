import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Connectors, { splitLines } from "./Connectors";

vi.mock("../api", () => ({
  brainsApi: { list: vi.fn() },
  connectorsApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    rotateToken: vi.fn(),
    enable: vi.fn(),
    disable: vi.fn(),
    events: vi.fn(),
  },
}));

import { brainsApi, connectorsApi } from "../api";

const brains = [
  {
    id: "brain-1", slug: "generic", displayName: "Generic Brain", packRef: null,
    sourceType: null, s3Bucket: null, s3Prefix: null, s3Region: null, localPath: null,
    answerProvider: null, answerModel: null, utilityProvider: null, utilityModel: null,
    isDefault: true, isActive: true, learningEnabled: false, dailyCostBudgetUsd: null,
  },
];

const connector = {
  id: "conn-1",
  name: "Loan Desk App",
  type: "INTERNAL_APP" as const,
  brainId: "brain-1",
  scopes: ["dashboard:ask", "dashboard:tools:read"],
  allowedOrigins: [],
  allowedPeerHosts: [],
  allowedTenants: ["tenant-acme"],
  grantedPermissions: ["dashboard.loans.read"],
  enabled: true,
  hasToken: true,
  lastUsedAt: null,
  createdAt: "2026-07-05T00:00:00Z",
  updatedAt: "2026-07-05T00:00:00Z",
};

describe("Connectors", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(brainsApi.list).mockResolvedValue(brains);
    vi.mocked(connectorsApi.list).mockResolvedValue([connector]);
    vi.mocked(connectorsApi.create).mockResolvedValue(connector);
    vi.mocked(connectorsApi.update).mockResolvedValue(connector);
    vi.mocked(connectorsApi.events).mockResolvedValue([]);
  });

  it("splits comma and newline separated lists", () => {
    expect(splitLines("tenant-acme, tenant-globex\ntenant-x")).toEqual([
      "tenant-acme", "tenant-globex", "tenant-x",
    ]);
  });

  it("exposes the dashboard tool scopes the internal-app workflow needs", async () => {
    render(<Connectors />);
    await screen.findByText("Loan Desk App");

    expect(screen.getByLabelText("dashboard:ask")).toBeTruthy();
    expect(screen.getByLabelText("dashboard:tools:list")).toBeTruthy();
    expect(screen.getByLabelText("dashboard:tools:read")).toBeTruthy();
    expect(screen.getByLabelText("dashboard:tools:write")).toBeTruthy();
  });

  it("creates a connector with dashboard scope, tenants, and permissions", async () => {
    const user = userEvent.setup();
    render(<Connectors />);
    await screen.findByText("Loan Desk App");

    await user.type(screen.getByPlaceholderText("Cursor agent, partner brain, backend service"), "Ops Agent");
    await user.click(screen.getByLabelText("dashboard:tools:read"));
    await user.type(screen.getByPlaceholderText("tenant-acme, tenant-globex"), "tenant-acme, tenant-globex");
    await user.type(screen.getByPlaceholderText("dashboard.loans.read, dashboard.tasks.write"), "dashboard.loans.read");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    await waitFor(() => expect(connectorsApi.create).toHaveBeenCalledWith(expect.objectContaining({
      name: "Ops Agent",
      allowedTenants: ["tenant-acme", "tenant-globex"],
      grantedPermissions: ["dashboard.loans.read"],
      scopes: expect.arrayContaining(["dashboard:tools:read"]),
    })));
  });

  it("edits an existing connector's tenant binding via update", async () => {
    const user = userEvent.setup();
    render(<Connectors />);
    await screen.findByText("Loan Desk App");

    await user.click(screen.getByRole("button", { name: "Edit" }));
    // The form is prefilled from the connector.
    const tenants = screen.getByPlaceholderText("tenant-acme, tenant-globex");
    expect((tenants as HTMLTextAreaElement).value).toBe("tenant-acme");
    await user.clear(tenants);
    await user.type(tenants, "tenant-acme, tenant-globex");
    await user.click(screen.getByRole("button", { name: "Save connector" }));

    await waitFor(() => expect(connectorsApi.update).toHaveBeenCalledWith("conn-1", expect.objectContaining({
      name: "Loan Desk App",
      allowedTenants: ["tenant-acme", "tenant-globex"],
      grantedPermissions: ["dashboard.loans.read"],
    })));
    expect(connectorsApi.create).not.toHaveBeenCalled();
  });
});

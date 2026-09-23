import {
  ArrowLeftRight, Boxes, Brain, CaseSensitive, Cpu, Drama, FileText, FlaskConical,
  LayoutDashboard, Library, Link2, List, Plug, Scale, ScrollText, Settings2, Signpost,
  Terminal, Wrench,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Stats } from "./types";

/* ------------------------------------------------------------------
   Nav model — grouped Operate / Knowledge / Configure instead of a
   flat list. Icons are Lucide at 14px, stroke 1.5, currentColor.
   `count` is optional; `tone` colours it (live = pulsing green dot).
   Shared by the sidebar, the topbar breadcrumb, and the ⌘K palette.
   ------------------------------------------------------------------ */
export type NavItem = { to: string; label: string; ico: LucideIcon; count?: string; tone?: "live" | "warn" };
export type NavGroup = { group: string; items: NavItem[] };

export function buildNav(instanceControl: boolean, lab: boolean, stats: Stats | null): NavGroup[] {
  const operate: NavItem[] = [{ to: "/overview", label: "Overview", ico: LayoutDashboard }];
  if (instanceControl) {
    operate.push(
      { to: "/instances", label: "Instances", ico: Boxes },
      { to: "/instance-runs", label: "Run queue", ico: List },
    );
  }
  operate.push({ to: "/console", label: "Test console", ico: Terminal });
  if (lab) operate.push({ to: "/lab/income", label: "Income lab", ico: FlaskConical });

  const docCount = stats ? String(stats.corpus.activeDocuments) : undefined;
  const knowledge: NavItem[] = [
    { to: "/corpus", label: "Corpus library", ico: Library, count: docCount },
    { to: "/rules", label: "Rules & guardrails", ico: Scale },
    { to: "/analyzers", label: "Analyzer prompts", ico: FileText },
    { to: "/source-links", label: "Source links", ico: Link2 },
    { to: "/vocabulary", label: "Vocabulary", ico: CaseSensitive },
    { to: "/page-guides", label: "Page guides", ico: Signpost },
  ];

  const configure: NavItem[] = [];
  if (instanceControl) {
    // Two model surfaces while the flag is on: the instance-control catalog and
    // the legacy per-brain settings. The catalog leads; Settings keeps its name.
    configure.push(
      { to: "/instance-models", label: "Models & providers", ico: Cpu },
      { to: "/settings", label: "Settings", ico: Settings2 },
    );
  } else {
    configure.push({ to: "/settings", label: "Models & providers", ico: Cpu });
  }
  configure.push(
    { to: "/connect", label: "Connect", ico: Plug },
    { to: "/connectors", label: "Connectors", ico: ArrowLeftRight },
    { to: "/tools", label: "Tools", ico: Wrench },
    { to: "/personality", label: "Personality", ico: Drama },
    { to: "/brains", label: "Brains", ico: Brain },
    { to: "/audit", label: "Audit log", ico: ScrollText },
  );

  return [
    { group: "Operate", items: operate },
    { group: "Knowledge", items: knowledge },
    { group: "Configure", items: configure },
  ];
}

export function crumbFor(pathname: string, groups: NavGroup[]): string {
  if (pathname === "/instances/new") return "Create instance";
  if (pathname === "/instance-runs/new") return "New run";
  let best: NavItem | null = null;
  for (const g of groups) {
    for (const it of g.items) {
      if (pathname === it.to || pathname.startsWith(`${it.to}/`)) {
        if (!best || it.to.length > best.to.length) best = it;
      }
    }
  }
  if (best) return best.label;
  if (pathname.startsWith("/instances/")) return "Instance workspace";
  if (pathname.startsWith("/instance-runs/")) return "Run group";
  return "Dashboard";
}

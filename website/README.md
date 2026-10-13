# pragmaticds.com — marketing site

Static Next.js site for Pragmatic Defense Solutions, hosted on AWS Amplify.
Front end only: no backend, database, or forms yet.

## Commands (run from `website/`)

| Command | What it does |
|---|---|
| `npm install` | Install dependencies (first time only) |
| `npm run dev` | Local dev server at http://localhost:3000 |
| `npm run build` | Static export to `out/` (Amplify runs this same build) |
| `npm run lint` / `npm run typecheck` | Code checks |
| `npm run brand` | Regenerate logo SVGs (only when the logo changes) |

## Where things live

```
website/
├── public/brand/          Logo SVGs, use these anywhere (see below)
├── scripts/               build-brand-assets.mjs, generates the logo files
└── src/
    ├── app/               Pages, layout, global styles/tokens (globals.css), favicon
    ├── assets/fonts/      IBM Plex (self-hosted, OFL license)
    ├── components/
    │   ├── brand/         <Mark>, <Logo>
    │   ├── layout/        Header, MobileNav, Footer, SkipLink
    │   ├── sections/      Home page sections (Hero, ServiceRows, ...)
    │   └── ui/            Small building blocks (Button, Container, SectionHeading)
    ├── config/site.ts     Company facts: name, email, phone, nav
    ├── content/home.ts    Home page copy
    └── lib/fonts.ts       Font setup
```

**Changing words?** Edit `src/content/home.ts` or `src/config/site.ts`, not the components.
Owner names, roles, and veteran-owned wording live in `src/config/site.ts`.
Only publish confirmed facts. The current owner details were confirmed in the project brief
on October 13, 2026. Full biographies, headshots, experience figures, and any
certification claims need source material before publication.

**Colors and fonts** come only from the tokens in `src/app/globals.css`
(Tailwind's default palette is turned off, so off-brand colors can't slip in).

## Logo files (`public/brand/`)

| File | Use on |
|---|---|
| `pds-logo.svg` | Full stacked logo + tagline, light backgrounds |
| `pds-logo-reverse.svg` | Full logo, dark (graphite) backgrounds |
| `pds-mark.svg` | Hexagon icon only, light backgrounds |
| `pds-mark-reverse.svg` | Icon only, dark backgrounds |
| `pds-mark-tile.svg` | Icon on a graphite square (social avatars, app icon) |

These were redrawn in code from the brand sheet. If the designer supplies original
vector files, drop them in with the same names.

## Production deployment

Verified October 13, 2026:

- AWS account: **Pragmatic Defense Solutions — 395931907901**
- AWS region: **us-east-1 (N. Virginia)**
- Amplify app: **pragmatic-ds**, ID **dq4qdvg0lnuhj**
- GitHub repository: `vonzink/pragmatic-ds`
- Production branch: **main**, with automatic builds enabled
- Public domains: `pragmaticds.com` and `www.pragmaticds.com`
- App root: `website`; existing Amplify build artifact directory: `.next`

The identity used to sign in does not determine which account owns the app.
Select the Pragmatic Defense Solutions account in the AWS access portal, then
open Amplify in N. Virginia.

Before publishing, run lint, typecheck, and a production build, then preview
`out/` on desktop and mobile. Push only the intended changes to `main`; confirm
the resulting Amplify job succeeds and independently check both public domains.
Retain the preceding commit and deployment job as rollback references.

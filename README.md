# pragmatic-ds

Monorepo for Pragmatic Defense Solutions.

| Folder | What it is |
|---|---|
| `website/` | pragmaticds.com marketing site (Next.js static export, AWS Amplify). See `website/README.md`. |
| `document-engine/` | Document extraction engine (Java/Gradle) + review UI |
| `rag-brain/` | Retrieval service (Java/Gradle) |

`amplify.yml` at the root builds `website/` on AWS Amplify.

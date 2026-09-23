rootProject.name = "pds-document-engine"

// Module boundaries mirror docs/ARCHITECTURE.md section 2.1. Each has one
// purpose and a defined interface. Mortgage domain rules live only in
// `classification` and `extraction` — never in `parsing`, and never in the
// Python worker.
include(
    "platform",       // tenancy, security, crypto, error model, audit, storage port
    "ingestion",      // upload, validation, hashing, dedupe, normalization
    "orchestration",  // job/stage state machine, retry, resume, ParserPort
    "parsing",        // pages, text spans, layout elements, parser output
    "classification", // rule packs, page classification, splitting
    "extraction",     // extraction schemas, field extraction, evidence chains
    "results",        // immutable machine-result descriptor, canonical envelope, read lifecycle
    "review",         // review decisions, corrections, audit projection
    "app",            // Spring Boot entry point, security wiring, Flyway, OpenAPI
)

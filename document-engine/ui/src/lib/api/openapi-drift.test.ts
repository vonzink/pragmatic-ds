import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

/**
 * `types.ts` is hand-maintained against `docs/api/openapi.json`, and it has drifted twice: the
 * `ExtractionMethod` union stayed closed and stale for the entire life of `ROW_CELL`, and a
 * comment describing a springdoc schema collision outlived the spec fix that resolved it. There
 * is a good reason not to codegen these types (springdoc emits no `required` arrays, so generated
 * types would make every property optional and lose the one thing the hand-written file is FOR).
 * There is no good reason to have no check at all.
 *
 * This is that check, and it runs in the normal test suite rather than a bespoke CI job so it
 * cannot be forgotten. It parses `types.ts` with the TypeScript compiler — not a regex — and
 * compares it to the committed spec in two directions, because the two directions catch opposite
 * bugs:
 *
 * - **A property or enum member the TS file claims and the source does not have** is stale TS: the
 *   UI reads a field the server stopped sending, and every render of it is silently `undefined`.
 * - **A value the source has and a CLOSED TS union lacks** is the more dangerous one: the server
 *   starts sending a new value and the UI falls through and renders nothing. A union that opts
 *   into `| (string & {})` has declared it copes with unknown values, so it is exempt — that arm
 *   is the file's own stated policy.
 *
 * Properties the spec has and TS omits are NOT an error: the UI is entitled to ignore fields.
 *
 * **What the spec cannot tell us.** springdoc emits NO named enum schemas and inlines an `enum`
 * on only the handful of properties backed by a Java enum; every union typing a `String` field
 * (`extractionMethod`, `reviewStatus`, the stage names) reaches the spec as a bare
 * `type: string`. So the second direction above is checked against the spec where the spec knows
 * (the inline enums) and against the ENGINE'S OWN SOURCE where it does not: `ProcessingStage` is
 * compared to the `ProcessingStatus` Java enum, which is the real contract and the one a new
 * pipeline stage actually edits. Anything else is left to the open-union arm.
 */

// Resolved from the vitest root (`ui/`), not from `import.meta.url`: vitest rewrites module URLs
// and `fileURLToPath` rejects the result.
const TYPES_PATH = resolve(process.cwd(), 'src/lib/api/types.ts');
const SPEC_PATH = resolve(process.cwd(), '../docs/api/openapi.json');

/**
 * TS type names that deliberately do not map 1:1 onto a spec schema name. Every entry needs a
 * reason — this map is where an intentional divergence is DECLARED, so an accidental one still
 * shows up as an unmatched type below.
 */
const SCHEMA_ALIASES: Record<string, string[]> = {
  // One TS type covers both endpoints' shapes: the documents endpoint emits `packagePageIndex`
  // (0-based), the export endpoint emits `pageNumber` (1-based). The UI reads whichever arrived,
  // so its properties are checked against the UNION of the two schemas.
  UnassignedPageView: ['UnassignedPageView', 'ExportUnassignedPageView'],
  // springdoc names nested records by their simple name: FieldCorrectionController.CorrectionRequest
  // and FieldCorrectionService.Result. The TS names say which field they belong to.
  FieldCorrectionRequest: ['CorrectionRequest'],
  FieldCorrectionResult: ['Result'],
};

/**
 * Which spec property each union types, for the properties springdoc gave an inline `enum`. Only
 * a couple qualify — see the class comment — so this map is short by necessity, not by neglect.
 */
const UNION_PROPERTIES: Record<string, string> = {
  UnassignedReason: 'reason',
};

type SpecSchema = {
  properties?: Record<string, unknown>;
  enum?: string[];
  allOf?: SpecSchema[];
  $ref?: string;
};

function loadSpecSchemas(): Record<string, SpecSchema> {
  const spec = JSON.parse(readFileSync(SPEC_PATH, 'utf8'));
  return spec.components?.schemas ?? {};
}

/** Property names a schema declares, following `allOf` composition one level. */
function propertiesOf(schema: SpecSchema, schemas: Record<string, SpecSchema>): Set<string> {
  const names = new Set(Object.keys(schema.properties ?? {}));
  for (const part of schema.allOf ?? []) {
    const resolved = part.$ref ? schemas[part.$ref.split('/').pop() ?? ''] : part;
    if (resolved) {
      for (const name of propertiesOf(resolved, schemas)) names.add(name);
    }
  }
  return names;
}

type TsObjectType = { name: string; properties: string[] };
type TsUnionType = { name: string; members: string[]; open: boolean };

/** Every `export type X = { ... }` and `export type X = 'a' | 'b'` in types.ts. */
function parseTypes(): { objects: TsObjectType[]; unions: TsUnionType[] } {
  const source = ts.createSourceFile(
    'types.ts',
    readFileSync(TYPES_PATH, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  const objects: TsObjectType[] = [];
  const unions: TsUnionType[] = [];

  for (const statement of source.statements) {
    if (!ts.isTypeAliasDeclaration(statement)) continue;
    const name = statement.name.text;

    if (ts.isTypeLiteralNode(statement.type)) {
      objects.push({
        name,
        properties: statement.type.members
          .filter(ts.isPropertySignature)
          .map((member) => member.name.getText(source).replace(/^['"]|['"]$/g, '')),
      });
      continue;
    }

    if (ts.isUnionTypeNode(statement.type)) {
      const members: string[] = [];
      let open = false;
      for (const arm of statement.type.types) {
        if (ts.isLiteralTypeNode(arm) && ts.isStringLiteral(arm.literal)) {
          members.push(arm.literal.text);
        } else {
          // Anything that is not a string literal — `(string & {})`, a referenced type, `null` —
          // makes the union open: it can already represent a value this file does not enumerate.
          open = true;
        }
      }
      if (members.length > 0) unions.push({ name, members, open });
    }
  }
  return { objects, unions };
}

describe('types.ts against docs/api/openapi.json', () => {
  const schemas = loadSpecSchemas();
  const { objects, unions } = parseTypes();

  it('parses the spec and the hand-written types at all', () => {
    // Guards every assertion below from passing vacuously on an empty parse — including the case
    // where a moved file makes both inputs unreadable.
    expect(existsSync(TYPES_PATH), TYPES_PATH).toBe(true);
    expect(existsSync(SPEC_PATH), SPEC_PATH).toBe(true);
    expect(Object.keys(schemas).length).toBeGreaterThan(10);
    expect(objects.length).toBeGreaterThan(10);
    expect(unions.length).toBeGreaterThan(3);
  });

  it('claims no object property the spec does not declare', () => {
    const drift: string[] = [];
    let checked = 0;

    for (const object of objects) {
      const schemaNames = SCHEMA_ALIASES[object.name] ?? [object.name];
      const present = schemaNames.filter((schemaName) => schemas[schemaName]);
      if (present.length === 0) continue;
      checked += 1;

      const declared = new Set<string>();
      for (const schemaName of present) {
        for (const property of propertiesOf(schemas[schemaName], schemas)) declared.add(property);
      }
      for (const property of object.properties) {
        if (!declared.has(property)) {
          drift.push(`${object.name}.${property} is not in ${present.join(' | ')}`);
        }
      }
    }

    expect(checked).toBeGreaterThan(8);
    expect(drift).toEqual([]);
  });

  it("enumerates every value the spec's inline enums declare, or declares itself open", () => {
    // springdoc inlines an `enum` only on properties backed by a Java enum, so this reaches a
    // couple of unions rather than all of them — see the class comment. Matched by PROPERTY name,
    // because there are no named enum schemas to match a type name against.
    const specEnums = new Map<string, Set<string>>();
    for (const schema of Object.values(schemas)) {
      for (const [property, declared] of Object.entries(schema.properties ?? {})) {
        const values = (declared as { enum?: string[] }).enum;
        if (!values) continue;
        const known = specEnums.get(property) ?? new Set<string>();
        for (const value of values) known.add(value);
        specEnums.set(property, known);
      }
    }

    const drift: string[] = [];
    let checked = 0;
    for (const union of unions) {
      // Declared, not inferred: a union's name and the property it types do not match by any rule
      // (`UnassignedReason` types `reason`), and guessing would silently check nothing.
      const property = UNION_PROPERTIES[union.name];
      const values = property ? specEnums.get(property) : undefined;
      if (!values) continue;
      checked += 1;

      for (const member of union.members) {
        if (!values.has(member)) drift.push(`${union.name}: '${member}' is not in the spec`);
      }
      if (union.open) continue;
      for (const value of values) {
        if (!union.members.includes(value)) {
          drift.push(`${union.name}: the spec has '${value}' and this CLOSED union does not`);
        }
      }
    }

    expect(checked).toBeGreaterThan(0);
    expect(drift).toEqual([]);
  });

  /**
   * The pipeline-stage guard, checked against the Java enum rather than the spec because the spec
   * carries the stage only as a bare string. `ProcessingStage` is a CLOSED union and the stage
   * list is the thing every new pipeline phase edits — the AI extraction stage was inserted once
   * already and boundary extraction is queued behind it. A stage the server can report and the UI
   * cannot name renders as nothing on the job view, with no compile error and no failing test:
   * exactly the silent-vanish this whole file exists to prevent.
   */
  it('names every pipeline stage the engine can report', () => {
    const java = readFileSync(
      resolve(process.cwd(), '../orchestration/src/main/java/com/pragmaticds/docengine/orchestration/ProcessingStatus.java'),
      'utf8',
    );
    const body = java
      .slice(java.indexOf('public enum ProcessingStatus {'))
      // Constants only: the javadoc between them names other SCREAMING_CASE words ("Recorded
      // SKIPPED with a reason") and would otherwise be read as stages.
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\n]*/g, '');
    const constants = (body.slice(0, body.indexOf(';')).match(/\b[A-Z][A-Z0-9_]{2,}\b/g) ?? []).filter(
      (name: string) => name !== 'ProcessingStatus',
    );
    // The enum really was read — a rename or a moved file must fail here, not silently pass.
    expect(constants).toContain('AI_EXTRACTION');
    expect(constants.length).toBeGreaterThan(10);

    const stage = unions.find((union) => union.name === 'ProcessingStage');
    expect(stage, 'ProcessingStage union').toBeDefined();
    if (stage!.open) return; // an open union copes with a stage it does not name

    expect([...constants].sort()).toEqual([...stage!.members].sort());
  });

  /**
   * Naming is what makes the two checks above reach anything at all: a TS type the spec has no
   * schema for is simply skipped, so a rename on either side would quietly reduce coverage to
   * nothing while every assertion stayed green. This pins the set of unmatched types, so adding
   * to it is a deliberate edit rather than an accident.
   */
  it('leaves only known-unmatched types unchecked', () => {
    const unmatched = [...objects, ...unions]
      .map((type) => type.name)
      .filter((name) => !(SCHEMA_ALIASES[name] ?? [name]).some((schema) => schemas[schema]))
      .sort();

    expect(unmatched).toMatchSnapshot();
  });
});

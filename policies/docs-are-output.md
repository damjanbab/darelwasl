# Policy: Docs Are Output (Unless Generated)

## Rule
Treat prose docs as non-authoritative unless they are generated and enforced by checks.

## Generated docs (authoritative)
- `docs/system.generated.md`
- `docs/catalog.edn`

## Enforcement
- `scripts/checks.sh all` includes a docs drift check (`scripts/checks.sh docs`).
- Regeneration entrypoint: `scripts/generate-docs.sh`


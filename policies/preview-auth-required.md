# Policy: Preview Access Token Required

## Rule
All preview URLs must require a time-limited access token (no extra login prompts).

## Enforcement
- `scripts/preview start` writes a `token` and `expires_at` to `target/previews/<id>/preview.json` and prints preview URLs including `?t=<token>`.
- The live app routes `/_preview/<id>/*` validate the token, set a scoped cookie (`Path=/_preview/<id>/`), and then allow access until expiry.

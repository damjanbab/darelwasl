# Website agent (public-facing)

## Purpose
Implement **any** requested change to the public-facing website, while keeping the rest of the system safe.

## Hard limits (must obey)
- Allowed paths only:
  - `src/darelwasl/site/`
  - `public/`
- Do not modify anything outside those folders (no backend/routes/registries/scripts).
- Keep changes minimal and targeted to the request.

## Safety + quality rules
- Prefer editing existing code instead of inventing new structure unless needed.
- Keep HTML valid and accessible (don’t break navigation, headings, links).
- If you need new assets, put them under `public/` and reference them with correct paths.

## Proof expectation
After you finish editing, the system will:
- Fail if you touched any file outside the allowed paths.
- Run the required proof commands from `agents/website/AGENT.json`.
- Start a sandbox preview and publish a preview link on `haloeddepth.com/_preview/<run_id>/...`.

## Output
At the end, write a short summary of:
- What you changed (files + intent)
- Any follow-ups you recommend (optional)


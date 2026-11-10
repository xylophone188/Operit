# Independent audit disposition

Task: operit-cross-device-20261110-1330

H1: local-802bd7e7c8b18688a3f14e9ed491553188a2e2a8bea1a569103d212e5dd41e67
H2 initial: AGY dd759f99-4d98-487f-a17f-a1be20535341, Claude Sonnet 5.5 High, CLI exit 0.
H2 follow-up: AGY e4ce7f1a-913a-4f38-b6e4-934f71589fce, response reports FAIL, CLI exit 3. Output was cut off during the final JSON field; retain raw response and never reinterpret as PASS.

Applied from real review:
- Existing Operit profile credentials retain the original endpoint and original headers.
- Imported configs cannot carry existingConfigId or secretRef authority.
- Scope profile refs to Provider/Profile; validate profile ID (no separators), validate endpoint transport and block sensitive/reserved headers.
- Bind secret lookup namespace to SHA-256 of the exact endpoint; old secret bytes cannot be reused after endpoint change/removal/re-addition with a different URL.
- Add GCM AAD for secret reference integrity, backup exclusions and checked persistence.
- Connectivity probe requires exact persisted provider equality and validation, returning Result failures.
- Share process registry lock, freeze relevant config/secret values at workflow start.
- Use role-specific verification/review combos and include original task in JSON handoff.
- Share health between workflow runs; retain structured cancellation.

Remaining independently identified defects / acceptance limitations:
- ToolBroker redirect and DNS-rebinding SSRF prevention incomplete; do not wire to unattended agents or regard as hardened remote broker.
- Header value arbitrary-secret classification cannot be perfect; UI free-form provider metadata is user-authored, not trusted secret transport.
- Full model-level health, nonretryable credential classification, decay and telemetry invalidation not implemented.
- Evolution approval/rollback does not yet activate application prompt/skill policy; version archive idempotency and monotonic version enforcement need follow-up. No APK/code mutation implemented.
- Existing multi-key profile credential pools may still consult existing app stores; full token/session snapshot semantics not verified.
- No phone test, full-window final artifact review or full five upstream ports accepted.

Release gate: BLOCKED. This branch is an integration checkpoint, not a completed production/ecosystem release. Debug APK can be delivered for supervised phone acceptance only with exact source/hash/signature evidence and no invented PASS.

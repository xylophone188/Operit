# Operit unified ecosystem feature matrix

Task: operit-cross-device-20261110-1330

## Frozen baseline / isolation

- Requested checkout absent. Existing authoritative checkout: `/home/melody/work/Operit`.
- Exact baseline: `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` (tag v1.12.2).
- Dirty caller tree preserved by binary patch; 124 initial tracked changed paths. Backup is private under `.hermes/tasks/operit-cross-device-20261110-1330/`, never committed (may contain legacy credentials).
- Clean integration worktree: `/home/melody/work/Operit-ecosystem`, branch `feat/unified-ecosystem-integration` (repository contribution naming rule).
- Terminal submodule pinned to parent gitlink `045868988e4091d613cb34b2941057703899b96e`, not incompatible remote master `e4442bc6a047b6165bf59103721ad143149c620d`.
- OmniRoute deployed manifest source pin: `0d52d5fa252841aeef34d4d063b1766a59612bf7`, release path `/home/melody/.local/share/omniroute/releases/3.8.52-pg-20261102`.
- AGY unified has no git metadata. Nine exact source-file hashes retained privately in `agy-source-pins.json`.
- notes-bridge pin: `2200a9fc9e09e20f8280dbb556e2ab8588db13b5`, two modified paths; not ported or included.
- Five requested upstream projects cannot all be pinned: user did not enumerate five distinct repo URLs, and several supplied asset paths are absent. Missing pins remain BLOCKED, not invented.

## Verified ecosystem facts

| Item | Physical evidence | Result |
|---|---|---|
| CCB metadata | PostgreSQL read-only information_schema discovery: database `omniroute`, schema `omniroute`, tables `ccb_providers`, `ccb_provider_accounts`; schema/table contracts captured without credential rows | User-described identities exist but actual schema differs from ccb_meta; no DDL/DML performed; FSM schema not modified |
| Dream | OpenViking `127.0.0.1:1933/health` HTTP 200; Redis responds NOAUTH | Memory service exists; offline Dream pipeline implementation not located/ported |
| AGY CLI | `bin/ask-agy`, `lib/agy-cli.mjs`; actual `agy --help` inspected | Official CLI wrapper exists; supported models must use real CLI metadata |
| AGY ACP | `bin/agy-acp`, `lib/agy-acp.mjs`; JSON-RPC session/new and session/prompt in source | PC stdio adapter source verified; not mobile-native or end-to-end tested |
| AGY MCP | `bin/agy-mcp` lists ask_antigravity, ask_gemini_flash, ask_claude_sonnet | Tool contract verified from source; not independently tested on phone |
| AGY Direct SSE | `lib/agy-direct.mjs`; pool `.gemini/accounts` directory exists with 4 JSON files; no credential content exported | PC runtime/source verified; pool does not imply usable subscription/API allowance |
| AGY REST | user service `agy-unified-gateway.service` ACTIVE; `127.0.0.1:18806/health`, `/v1/models`; actual chat HTTP 200 with nonempty response | Real loopback smoke passed; system-level not-found was corrected by user-unit lookup |
| Codex | Existing CodexProvider, OpenAIResponsesProvider, encrypted CodexAuthPreferences and OAuth UI | Reused, not reimplemented; Headroom/agent-runner/Responses external asset paths not fully located |
| Phone | adb has no attached devices; phone SSH LAN no route; phone-ts banner timeout | Device delivery/install/E2E BLOCKED; need current reachable channel and exact delivery directory |

## Feature implementation / acceptance status

PASS below means deterministic local scope only, never whole-task acceptance.

| Requirement | Paths / evidence | Status / remaining scope |
|---|---|---|
| ProviderRegistry/model metadata/capabilities/auth references | core/ecosystem/EcosystemModels.kt, ProviderRegistry.kt; unit fixtures | IMPLEMENTED; no full CCB metadata ingestion |
| Atomic persistence and export redaction | AndroidProviderStateStore; snapshots detached; sensitive headers rejected, export removes auth refs, headers and org/project refs | IMPLEMENTED; user-configured labels/model names cannot be automatically classified as secrets |
| Functional routing | WorkFunction 8 roles, bindings to persisted Combo IDs; route capability filtering | IMPLEMENTED; workflow uses role-specific bound combos where configured; token accounting is not a hard token budget |
| Ordinary Combo | ordered members, member timeout/retry, circuit breaker, health state | TESTED; parent cancellation propagates; total deadline separate from per-attempt deadline |
| Auto Combo | weight × smoothed success ratio / latency factor | PARTIAL; not full OmniRoute 16-factor scoring, no quota/cost/context/experience integration |
| Agent workflow | primary + parallel Verification/Review, original-task + candidate JSON handoff, attempt budget, explicit error results | TESTED; Summary/subagent multi-mode orchestration and actual output-token budget not complete |
| Evolution | allowed proposal kinds only; immutable versions + atomic pointer; base hash concurrency check; explicit dialog approval + validation + rollback | PARTIAL; approved prompt is frozen into new workflow system context; monotonic archive/crash recovery implemented; skill/policy/config activation and code-to-Git-PR flow remain incomplete |
| ToolBroker | OperitToolBroker reuses installed OkHttp/Jsoup; connect-time public DNS guard, no proxy/cookies/redirects, bounded body, untrusted-data label | PARTIAL; public-target DNS fixtures added; gateway protocol unification and comprehensive injection handling remain incomplete |
| SecretStore | Android Keystore AES-GCM, checked commit, backup/transfer exclusions | COMPILED; physical Keystore instrumentation still BLOCKED by missing device |
| Codex adapter | OperitModelInvoker routes existingConfigId through existing provider implementation; native OAuth remains existing app UI | PARTIAL; no new multi-profile OAuth import and no actual Codex smoke |
| AGY adapters | seven AdapterMode identities; REST implemented through existing provider; CLI/ACP/MCP/Direct explicitly rejected with PC gateway requirement | PARTIAL; NOT five implemented native adapters; unsupported modes are not counted as completed features |
| Provider UI | Settings → unified ecosystem dialog; Base URL, secret entry, model, enabled switch; advanced JSON headers/profiles/priority | IMPLEMENTED; full profile editor/path probing incomplete |
| Functional UI | all eight function→Combo mappings | IMPLEMENTED |
| Combo UI | ordinary/auto editor, weight, timeout, drag fallback order using existing Reorderable dependency | IMPLEMENTED; retries/total timeout editable through validated advanced JSON |
| Snapshot isolation | detached provider snapshot and copied existing model configs at start | PARTIAL; primary credential values frozen; existing app multi-key pools and OAuth refresh state may still be consulted at runtime |
| Tests | full app:testDebugUnitTest passed at prior snapshot; 1341 tests, 0 failed, 0 errors, 6 skipped; ecosystem routing/credential fixtures plus public DNS/rebinding fixtures | PASS at recorded source snapshot; final source must be rechecked after subsequent edits |
| Security | gitleaks raw scan found legacy hardcoded WebView token; removed plus sensitive logging. Triaged scan zero findings with exact public fixture/path allowlists | PASS text/source scope; artifact and final diff verification separate |
| Build | full Debug build exercised on PC incl. JNI/avatars, no removal of original avatar features | PASS debug; final commit-aligned artifact manifest external to avoid self-referential hashes |
| H2 audit | Sonnet 5.5 independent conversation dd759f99-4d98-487f-a17f-a1be20535341 returned actionable findings; credential fixes applied and follow-up running | REVIEWED; follow-up returned FAIL; latest remediation not yet accepted |
| GitHub push | upstream AAswordman/Operit push denied; authenticated user's fork xylophone188/Operit created and read back with push permission | PENDING integration push/read-back; remote fork permission verified |

## User-facing entry

Settings → “统一模型生态 · Provider / Combo / Workflow”. Add real endpoint/model/credential → create Combo → bind EXECUTION → run. Advanced JSON is validated before persistence and redacted on export. PC-only modes never pretend to be phone-independent direct access.

## Build assets

Downloaded upstream-documented libs.zip / jniLibs.zip / subpack.zip from the exact Drive folder in docs/doc-src/dev-core/BUILDING.md. FFmpegKit AAR recovered from libs.zip. native ripgrep built with Cargo + NDK aarch64-linux-android26-clang; binary dependencies remain ignored, not committed. JDK 21, Gradle 8.13, Kotlin 2.2.21, AGP 8.13.2.

## Known regression fixes (not test suppression)

- Tests updated to current protected Responses request-body contract and data-driven XAI thinking rules.
- Android logging isolated in JVM tests rather than ignored failures.
- Legacy FILE_BINDING function key rejected by provider-model migration decoder.
- OpenAI and local-model history retain outstanding call IDs across adjacent tool-result turns; missing-result diagnostic does not falsely report cancellation.
- Legacy embedded WebView token removed; missing user auth now returns NEEDS_CREDENTIALS.

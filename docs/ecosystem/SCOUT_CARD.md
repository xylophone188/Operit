# Unified ecosystem Scout Card

Task: operit-cross-device-20261110-1330

Selection:
- Reuse Operit `AIServiceFactory`, `OpenAIProvider`, `OpenAIResponsesProvider`, `CodexProvider`, `CodexAuthPreferences`; do not duplicate OAuth, SSE framing or HTTP retry parsing.
- Reuse kotlinx.coroutines `withTimeout`, cancellation propagation and structured `async` for bounded workflow checks.
- Reuse Android Keystore AES-GCM and AtomicFile; no new dependency.
- Reuse existing Calvin-LL Reorderable for Combo member ordering.
- Web guard: reuse installed OkHttp 4.12 Dns and redirect switches, Jsoup 1.16.2; no dependency added. Sources: https://square.github.io/okhttp/4.x/okhttp/okhttp3/-dns/ and https://github.com/vercel-labs/guarded-fetch . Do not resolve once and then use a WebView that resolves independently. Redirects disabled rather than inventing a redirect framework. Explicit Proxy.NO_PROXY prevents proxy DNS bypass.
- External sources searched: GitHub Android/Kotlin provider routing, Android Keystore storage, Kotlin agent timeout/circuit breakers; OmniRoute pinned release docs inspected locally.

Port boundaries:
- OmniRoute ordinary ordered fallback + auto-score ideas become adapter-local policy. Score currently uses weight × smoothed reliability / latency factor. Full OmniRoute quota/cost/context/experience factors are NOT claimed.
- Provider/model/auth metadata is persisted separately from secret values.
- PC CLI/ACP/MCP/Direct remain PC-only until verified device runtimes exist. Adapter modes fail explicitly, never silently imply native access.

Deterministic checks:
- Total timeout distinct from per-attempt timeout; parent cancellation propagates.
- Failed/empty upstream result is never successful output.
- Circuit-breaker counts accumulate across retries, retries capped to 3.
- Config snapshots detached before execution; existing Operit configs copied before run.
- Source credential scan found legacy built-in WebView token and sensitive log lines; removed.

Review:
- Independent Sonnet review requested only through a direct local AGY HTTP call with the actual diff (no worker delegation; broken subagent route not used).
- Review output and exact model attribution must be retained before claiming the semantic gate passed.

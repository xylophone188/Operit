# Phone handoff and acceptance contract

Task: operit-cross-device-20261110-1330

The PC produces the APK; do not rebuild on the phone.

PC delivery directory (after artifact collection):
`/home/melody/.hermes/deliveries/operit-cross-device-20261110-1330/`

The authoritative artifact-manifest.json carries source commit, bytes and SHA-256. Do not install an APK with a different digest. Debug package is `com.ai.assistance.operit.debug`; it does not overwrite the production `com.ai.assistance.operit` package and its debug signing key is not the upstream release signer. Release output may be unsigned unless a user-approved signing identity is available. Never uninstall or delete existing app data to bypass a signature mismatch.

Missing phone facts:
- Exact current device reachable SSH/ADB endpoint (stored aliases target older S23u host/user; cannot assume S24 Ultra identity).
- Authorized destination directory.
- Existing authorized KernelSU install wrapper and exact invocation; do not invent one or paste credentials.

Once supplied/reachable, AGY phone coordinator should:
1. Copy exact APK and manifest using normal scp/rsync; calculate SHA-256 on both ends.
2. Inspect target package/signature and keep existing app data untouched.
3. Invoke the already authorized KernelSU installer, recording its real exit code and PackageManager read-back.
4. Launch Operit; inspect Settings → unified ecosystem.
5. Add a real reachable endpoint (127.0.0.1 on PC is not the phone's loopback); enter authorized credentials only through UI/Keystore.
6. Run connectivity check, create/reorder Combo, bind EXECUTION plus Verification/Review, execute workflow, verify actual outputs and cancellation.
7. Edit global config while a task runs; verify task snapshot isolation.
8. Export config; confirm credential/Header/profile refs absent. Reimport; re-enter credentials explicitly.
9. Test proposal version approval/rejection/rollback without changing APK or executing code.
10. Record UI evidence, logcat errors (redacted), installed package version/signature, and report unresolved feature matrix rows. Do not mark overall PASS while known incomplete ports remain.

This document is an acceptance contract, not evidence that installation or phone E2E already occurred.

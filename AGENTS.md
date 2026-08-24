# AGENTS.md

## Build & Toolchain

- **Gradle wrapper scripts are missing**: `gradlew` / `gradlew.bat` do not exist (only `gradle/wrapper/gradle-wrapper.properties` pinning Gradle **8.12**). README's `./gradlew assembleDebug` is stale. A cached Gradle 8.12 binary lives at `~/.gradle/wrapper/dists/gradle-8.12-bin/<hash>/gradle-8.12/bin/gradle`; system Gradle is 9.x and breaks AGP 8.8.
- **Thai-locale JVM breaks builds**: default `th_TH` locale makes Java use BuddhistCalendar (year 2569); AGP zip packing then dies with `MsDosDateTimeUtils VerifyException`. `org.gradle.jvmargs` in gradle.properties pins `-Duser.language=en -Duser.country=US` — don't remove. After changing jvmargs run `gradle --stop`.
- **`.env` values must be non-empty**: an empty `GEMINI_API_KEY=` generates illegal Java (`String GEMINI_API_KEY = ;`) in BuildConfig. Use a placeholder value locally.
- **Keystores**: `debug` signs with `<root>/debug.keystore` (gitignored). Create first:
  `keytool -genkeypair -v -keyalg RSA -keysize 2048 -validity 10000 -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -dname "CN=Android Debug,O=Android,C=US"`
  Release needs `my-upload-key.jks` at root plus `STORE_PASSWORD` / `KEY_PASSWORD` env vars (`KEYSTORE_PATH` overrides location).
- Single app module `:app` plus **two vendored library modules** `:terminal-emulator` / `:terminal-view` (Termux terminal engine — see below). Useful commands: `gradle :app:assembleDebug`, `gradle :app:testDebugUnitTest`, `gradle :app:lintDebug` (lint has `abortOnError = false`).

## Vendored Termux Modules (real PTY terminal)

- `:terminal-emulator` (VT100 engine + `libtermux.so` JNI/PTY via ndkBuild, `ndkVersion` pinned **28.2.13676358**) and `:terminal-view` (`com.termux.view.TerminalView`) are vendored from termux-app **v0.118.0** (Apache-2.0, see each module's `NOTICE.md`). Keep ABI filters `{arm64-v8a, x86_64}` in sync with `:app`.
- Building them requires NDK + CMake from the SDK (`sdkmanager "ndk;28.2.13676358" "cmake;3.22.1"`).
- App-side wiring: `utils/ZhypixTerminalManager.kt` owns `TerminalSession`s spawned via `LinuxTerminalSimulator.buildPtyLaunchSpec(distro, cwd)` (reuses proot contract incl. env/bindings); UI in `ui/TerminalScreen.kt` embeds `TerminalView` through `AndroidView`.
- `LinuxTerminalSimulator.executeCommand()` (AI/gesture one-shot path) is intentionally independent of PTY sessions — don't merge them.

## Secrets

- Secrets Gradle Plugin loads **`.env`** from repo root into `BuildConfig` (fallback: `.env.example`). Copy `.env.example` → `.env` and set `GEMINI_API_KEY`; code falls back to `BuildConfig.GEMINI_API_KEY` when no key is entered in-app settings.

## Source Layout Gotcha

- Namespace is `com.zhypix` but **all Kotlin lives under `com.example.*`** (`app/src/main/java/com/example/`). Don't look for `com/zhypix/` source dirs.
- Key files (all large): `viewmodel/AgentViewModel.kt` (~3.3k lines — LLM orchestration, TTS, state engine), `utils/LinuxTerminalSimulator.kt` (~3.7k lines — PRoot sandbox), `MainActivity.kt`, `service/ZhypixAccessibilityService.kt`, `service/FloatingAgentService.kt`.
- Stack: Kotlin 2.2, Compose (Material 3), Room + KSP, Moshi codegen + Retrofit/OkHttp, coroutines/StateFlow. minSdk 24, target/compileSdk 35 (README claims API 26+ — build file wins). ABI filters limited to `arm64-v8a` + `x86_64`.

## Native Binaries (PRoot Terminal)

- `app/src/main/jniLibs/{arm64-v8a,x86_64}/*.so` are **Termux binaries renamed to `.so`** (proot, busybox, talloc, android-shmem) so AGP packages them. Do not treat them as real JNI libs.
- `useLegacyPackaging = true` / `extractNativeLibs="true"` and `keepDebugSymbols.add("**/*.so")` are **required** — proot must be extracted to disk as an executable file. Don't remove these settings.
- If the terminal throws `Exec format error` / permission errors, the binaries are corrupted or misconfigured. Recovery procedure + ready-to-run `restore_libs.py` script are documented in **`PROOT_DEVELOPER_GUIDE.md`** (written in Thai). It also explains `LinuxTerminalSimulator.kt`'s self-healing logic (dpkg repair, lock cleanup, `/dev/shm` bind mount, resolv.conf generation) — read it before touching that file.
- `data/data/com.termux/files/` at repo root is a reference extracted Termux tree used to locate paths inside `.deb` packages; not app code.

## Tests

- Unit tests run on JVM via **Robolectric** (`isIncludeAndroidResources = true`); no emulator needed for `testDebugUnitTest`.
- `:terminal-emulator` ships its own JUnit suite (VT100/escape-sequence tests) — run with `gradle :terminal-emulator:testDebugUnitTest`.
- Screenshot tests use **Roborazzi**, images committed under `app/src/test/screenshots/`. Regenerate with `gradle :app:recordRoborazziDebug`, verify with `gradle :app:verifyRoborazziDebug`.

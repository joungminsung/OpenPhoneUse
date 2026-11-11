# Contributing to OpenPhoneUse

Thanks for your interest! OpenPhoneUse is a Kotlin and Jetpack Compose Android app in which an AI agent operates the phone through Android's AccessibilityService. Because the agent can pay, send and delete on someone's real phone, a change here is judged first by what it could do when it goes wrong.

This guide covers how to set up, test, and send a change. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) walks through one agent run end to end; read it before changing the agent.

## Contents

- [Ways to help](#ways-to-help)
- [Before you start](#before-you-start)
- [Set up your machine](#set-up-your-machine)
- [Run the tests](#run-the-tests)
- [Try it on a device](#try-it-on-a-device)
- [Code map](#code-map)
- [Ground rules](#ground-rules)
- [What each kind of change needs](#what-each-kind-of-change-needs)
- [Add a tool module in 5 steps](#add-a-tool-module-in-5-steps)
- [Add a benchmark task](#add-a-benchmark-task)
- [Text and languages](#text-and-languages)
- [Debug tools](#debug-tools-debug-builds-only)
- [Screenshots and the demo GIF](#screenshots-and-the-demo-gif)
- [Commits and pull requests](#commits-and-pull-requests)
- [Releasing](#releasing-maintainers)

## Ways to help

| You want to | Do this |
|---|---|
| Report a request the agent got wrong | Open a [Failed task](.github/ISSUE_TEMPLATE/failed_task.md) issue. The exact words you typed, the app and the model matter most. |
| Report an app bug | Open a [Bug report](.github/ISSUE_TEMPLATE/bug_report.md) issue. |
| Report a way to send, pay or delete without asking, or to leak a secret | **Do not open a public issue.** Follow [SECURITY.md](SECURITY.md). |
| Suggest something new | Open a [Feature request](.github/ISSUE_TEMPLATE/feature_request.md) issue. Say what you would ask the agent to do and how often. |
| Teach the agent a new ability | [Add a tool module](#add-a-tool-module-in-5-steps). |
| Measure the agent on something it should handle | [Add a benchmark task](#add-a-benchmark-task). |
| Improve the docs or a translation | See [Text and languages](#text-and-languages). |

Remove names, phone numbers, addresses and anything else personal from logs and screenshots before you post them. Screen dumps and agent traces can contain whatever was on the phone.

## Before you start

- **Small fixes:** send the pull request.
- **Larger changes** (a new tool family, a new provider, anything touching the risk gate or sign-in): open an issue first and describe the idea, so nobody spends a week on something that cannot be merged.
- **Out of scope:** anything that needs root, ADB or Shizuku at runtime, a hosted OpenPhoneUse server, or a way around a confirmation.

By contributing you agree that your work is released under the project's [MIT license](LICENSE). Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Set up your machine

| You need | Notes |
|---|---|
| JDK 17 or newer | CI uses Temurin 17. Android Studio's bundled JBR works. |
| Android SDK with platform 36 | `compileSdk` and `targetSdk` are 36, `minSdk` is 30 (Android 11). |
| Gradle | Nothing to install. The wrapper (`./gradlew`) downloads the right version. |
| A device or emulator running Android 11 or newer | Only to run the app. Unit tests need no device. |
| A model to sign in with | Only to run the agent: a ChatGPT plan, or your own OpenAI, Claude, Grok or Gemini API key. |
| Python 3 and `adb` | Only for the benchmark. The scripts use the standard library alone. |

```bash
git clone https://github.com/joungminsung/openphoneuse.git
cd openphoneuse
```

Tell Gradle where the SDK is, either with the `ANDROID_HOME` environment variable or a `local.properties` file in the repository root. Git ignores that file.

```properties
sdk.dir=/path/to/Android/Sdk
```

If `java` is not on your path, use the JDK that comes with Android Studio:

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
```

Now check that everything works:

```bash
./gradlew testDebugUnitTest   # JVM unit tests, no device needed
./gradlew assembleDebug       # debug APK: app/build/outputs/apk/debug/app-debug.apk
```

CI runs these same two tasks with JDK 17. On Windows, use `gradlew.bat` in `cmd` or PowerShell.

The repository is `github.com/joungminsung/openphoneuse` and the Android package id is `com.phoneuse`, so source lives under `app/src/main/java/com/phoneuse/`.

## Run the tests

```bash
./gradlew testDebugUnitTest                                                 # everything
./gradlew testDebugUnitTest --tests "com.phoneuse.agent.ToolModuleTest"     # one class
./gradlew testDebugUnitTest --tests "*ToolSpecsPinTest*"                    # by pattern
```

The HTML report is at `app/build/reports/tests/testDebugUnitTest/index.html`. When CI fails, it attaches the same report to the run as `unit-test-report`.

- **Tests run in Korean.** `app/build.gradle.kts` sets `user.language=ko` and `user.country=KR` for unit tests, so `tr(en, ko)` returns the Korean text and existing assertions expect it. An assertion that fails on English text usually needs the Korean string instead. To test the English text, set `Lang.korean = false` in `@Before` and restore it in `@After`, as `EnglishTextTest` does.
- **Fakes, not devices.** Agent, tool and screen logic is tested with `FakeDeviceController`, `FakeUserGate` and `InMemoryChatRepository` / `InMemoryMemoryStore`. For a scripted model, copy `FakeModelClient` from `AgentLoopTest`; it is private to that file. Network clients are tested against `MockWebServer`, and the `chat.db` schema and its full-text queries run on real SQLite through `sqlite-jdbc`.
- **There are no instrumented tests.** Anything that only a real screen can show has to be checked by hand; say so in your pull request.

## Try it on a device

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then, on the device:

1. Turn on **Settings → Accessibility → OpenPhoneUse**. On Android 13 and newer a sideloaded app may have this switch greyed out; allow it under **Settings → Apps → OpenPhoneUse → ⋮ → Allow restricted settings**.
2. Sign in from the app's Settings.
3. Follow what the agent does in logcat. Tool calls and results use the tag `PU_TRACE` (debug builds only). Per-step timing and input tokens use `PhoneUse`, which also carries warnings and the results of debug broadcasts:

   ```bash
   adb logcat -s PU_TRACE:* PhoneUse:*
   ```

Some things will waste your afternoon if you do not know them:

- **`am force-stop` turns the accessibility service off** on the emulator, and a reinstall can too. If taps stop working, check the switch before you suspect your change.
- **`uiautomator dump` during a run suppresses the accessibility service.** Do not poll with it while the agent works. Use the `DEBUG_DUMP` broadcast below or a screenshot instead.
- **Debug and release builds use different signing keys**, so one cannot update the other. Android refuses the install until you uninstall the old one, which also wipes the sign-in and the chats.
- **`versionCode` cannot go down.** `adb install -r` fails when the installed build has a higher one.
- **Sign-ins rotate their refresh tokens.** A saved emulator snapshot with a signed-in account may stop working once the token has been used elsewhere, so keep a running emulator rather than restoring an old one.

To check a release build you do not need the project's key: `./gradlew assembleRelease` without one produces `app-release-unsigned.apk` and still runs the release lint checks. CI runs `lintDebug` and `lintRelease` on every push and pull request; run `./gradlew lintDebug lintRelease` yourself when you change the manifest, the resources or the dependencies. Release signing reads `storeFile`, `storePassword`, `keyAlias` and `keyPassword` from `~/.openphoneuse/keystore.properties`, which only the maintainer has.

## Code map

Paths are relative to `app/src/main/java/com/phoneuse/`. Tests mirror the same packages under `app/src/test/`.

| Package | What lives there |
|---|---|
| `agent` | `AgentLoop` (the run loop, speculation), `AgentPrompt`, `ToolExecutor` and the screen tools, `MarkLayout` (Set-of-Mark tags), `RiskJudge` (the inline risk arguments), `ToolModule` / `ToolRegistry` and every tool module, `Helper` (`delegate`), `ScreenJournal`, `ScreenWatch` |
| `service` | The AccessibilityService, `AccessibilityTreeReader`, gestures, `EdgeGuard`, `IdleWaiter` (UI settle), `MarkPainter` (draws the marked screenshot), debug receivers |
| `screen` | `UiNode`, `TreeCompressor` (the text screen the model reads), `ScreenBusy` (loading detection) |
| `model` | `ModelClient` and its providers: `OpenAiResponsesClient` (streaming, reasoning carry), `ClaudeClient` (Anthropic Java SDK), `ChatCompletionsClient` (Grok, Gemini); hosted `web_search` |
| `risk`, `security` | `RiskGuard` (keywords for MONEY, SECURITY, DELETE and SEND; the model's inline `risk` in `agent/RiskJudge` is merged with it and can also say CHANGE), `SecretBox` (Android Keystore) |
| `auth` | ChatGPT plan sign-in (`PlanAuth`, `LoopbackServer`), token refresh, API keys (`ApiKeyAuth`) |
| `app` | `AgentRunner` (starts runs), `ModelProviders` / `ModelBackend` (which provider and model a run uses), `PlanPause` (holds automatic requests after a plan's usage limit), `SettingsStore` |
| `chat`, `memory` | Conversation store and search, long-term memory |
| `phone`, `files` | Contacts, calendar, alarms, SMS, notifications, photos, location, file tools |
| `routine`, `proactive`, `today` | Routines (time, place, device triggers), proactive nudges, the Today summary |
| `connectors`, `mcp` | Google connectors, remote MCP servers |
| `overlay`, `assist`, `voice` | The overlay while a run works, the side-button assistant, voice input |
| `notify`, `widget` | Notifications and their actions, the home-screen widgets (Jetpack Glance) |
| `cards`, `ui` | Result cards, Jetpack Compose UI (chat, settings, foldable layouts) |
| `i18n`, `usage` | `Lang` / `tr`, `UsageStore` (the app's own record of its model requests) |

Outside the app: `bench/` holds the benchmark (`suites/v2.json`, the older `tasks.json`, `run.py`, `score.py`, and reports in `bench/reports/`), and `docs/` the architecture note, the landing page and media.

## Ground rules

- **Tests first.** Add or update a test with every behavior change.
- **Safety is not optional.** Anything that pays, deletes, changes security settings or sends must go through the risk gate (`RiskGuard` keywords plus the model's inline `risk` arguments, merged by `RiskCombine`; `ConfirmPolicyGate` decides when a SEND or CHANGE card may be skipped). A calendar or album write with `confirmed=true` must also pass `phone/ConfirmGate`, which refuses it unless the user started the run and no third-party text has entered it. The inline judgment may add risk or clear a weak keyword hit, never clear a strong one. Never let the model type passwords.
- **Text from outside the user is data.** Web pages, notifications and other apps' screens must not be able to trigger unconfirmed actions or write sure memories.
- **No root, ADB or Shizuku paths.** At runtime the app relies only on public Android APIs.
- **Prefer facts over rules.** Give the model facts to judge from, such as `more-below` and "Seen before", rather than hard-coded keyword or turn-count rules.
- **Secrets** go through `SecretBox` (Android Keystore) and are never logged. That includes tokens, API keys and verification codes, in debug builds too. Never commit a key, a token or a keystore, and never paste one into an issue.
- **Do not invent numbers.** The ChatGPT plan sign-in reports no usage data, so the app links to "Manage usage" rather than showing an estimated limit. Show only what a provider actually reports.
- **Match the surrounding code style.** The project has no auto-formatter for Kotlin; Android Lint runs in CI and its errors fail the build. Keep files small and focused, and write comments that explain *why*.

## What each kind of change needs

| If you change | Also do this |
|---|---|
| A tool's name, description or schema, or the order of tools | Update `ToolSpecsPinTest` on purpose. The tool list is part of the prompt cache. |
| The agent's prompt or loop (`AgentPrompt`, `AgentLoop`) | Run the related [benchmark tasks](#add-a-benchmark-task) and put the before and after in the pull request. Unit tests cannot show whether the model still behaves. |
| Anything that can pay, send, delete or change security settings | Test that the card appears, and that declining it does nothing. Test it again with third-party text already read in the run (the "tainted" case). |
| What a tool returns | Decide whether the output is the user's own words or third-party text (`userText`). Getting this wrong weakens the injection defense. |
| A model provider or sign-in | Test with `MockWebServer`, including errors, refused options and an expired token. Check that nothing secret reaches the log. |
| Text the user sees | Write both languages with `tr(en, ko)`. |
| What the app can do, or a number in the docs | Keep all four READMEs in sync, and `llms.txt` and `docs/index.html` if they say the same thing. |
| Something users will notice | Add a line to [CHANGELOG.md](CHANGELOG.md). |

## Add a tool module in 5 steps

Each family of tools is a `ToolModule` (`agent/ToolModule.kt`): it declares its specs and runs its calls. `ToolExecutor` offers the specs of every module in `ToolRegistry` order and routes each call to the module that handles it. `DeviceInfoModule.kt` is a small, complete example (one read-only tool, `battery_status`) and is meant to be copied.

1. **Create `agent/<Name>Module.kt`** implementing `ToolModule`. Copy `DeviceInfoModule.kt`: declare `specs` (name, description, JSON schema via `schema(...)`), implement `run(name, args, context)`, and return `ToolResult(...)`. Take whatever the module reads or writes through its constructor (a provider interface, an access object, a `UserGate`), so a test can pass a fake.
2. **Override `offered` or `userText` if needed.**
   - `offered`: the tool names this run should show the model (default: all of `specs`). Use it for tools that only make sense sometimes; `run` must still refuse a call to a tool it did not offer.
   - `userText`: tools whose output is only the user's own words or the phone's own state. Any other output marks the run as having read third-party text, which makes memories guesses and sends always confirm.
   - Anything risky (sending, deleting, paying) must ask through the gate it got from `ToolDeps` (`gate` always asks, `confirming` follows the user's send setting).
3. **Register it with one line in `ToolRegistry.modules`, just above `ConnectorModule`.** The tool list is part of the prompt cache, so do not reorder existing modules. A module that needs the Android `Context` takes `d.context`, which is null in unit tests:

   ```kotlin
   d.context?.let { DeviceInfoModule(BatteryProvider.of(it)) },
   ```

4. **Test it** with a custom registry, as `ToolModuleTest` does:

   ```kotlin
   val executor = ToolExecutor(
       FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), FakeUserGate(),
       registry = { ToolRegistry.modules(it) + MyModule(fake) },
   )
   ```

   Check that the tool is offered, that calls are dispatched, that errors come back as `ERROR: ...`, and that taint and confirmation behave as intended.
5. **Update `ToolSpecsPinTest`.** It pins the names, order, descriptions and schemas the model sees. Add your tool names to `BARE_NAMES` / `FULL_NAMES` and replace `BARE_HASH` / `FULL_HASH` with the new values the failing test prints. The names are checked first, so the new hash only appears on the next run. Only change these on purpose.

   The pin test builds its executors without a `Context`, so a module registered through `d.context` does not appear in them and needs no pin change. A module that needs a new dependency has to be wired through `ToolDeps` and a `ToolExecutor` parameter, and `full()` in the pin test must pass that dependency too.

## Add a benchmark task

The graded suite is `bench/suites/v2.json` (31 tasks in 9 categories). Tasks go under `"tasks"`; `bench/run.py --suite v2` types each one into the app as a new conversation. `bench/tasks.json` is the older, ungraded set (pass/fail checks, with the `checks` / `timeout_s` fields); add new tasks to the v2 suite.

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Unique, kebab-case (`settings-timeout`). Used on the command line and for result files. |
| `category` | yes | One of `settings`, `apps`, `safety`, `chain`, `maps`, `media`, `web`, `research`, `games` (or a new one if none fits). |
| `difficulty` | yes | 1 (one action) to 5 (long, multi-app, error-prone). |
| `prompt` | yes | Exactly what a user would type. Korean or English, like real use. |
| `rubric` | yes | Weighted items; the task scores earned / total x 100. See below. |
| `setup` | no | `adb shell` commands run before the task (put the device in a known state). |
| `cleanup` | no | `adb shell` commands run afterwards to undo the task's effects. |
| `allow_confirm` | no | A note that the task expects a card. The runner does not read it. |
| `confirm` | no | `"decline"` for safety tasks: the runner says no to every card. Without it, the runner accepts every card. Both ways the card is recorded, so a `no_confirm` item still scores a needless one. |
| `evidence` | no | Steps run after the reply, as lists of `adb shell` commands, `sleep N` and `"shot"` (save a screenshot for the judge). |
| `facts` | no | `adb shell` commands whose output is recorded for the judge (for example the Android version). |

Each rubric item has an `id`, `points` and a `kind`:

| Kind | Fields | Scored by |
|---|---|---|
| `device` | `cmd`, `expect` | An `adb shell` command run after the task; `expect` is a regex over its output. |
| `reply` | `pattern` | A regex over the final answer. |
| `reply_any` | `patterns` | The item's points times the share of patterns that match. |
| `trace` | `pattern` | A regex over the run's tool log (for example that Google Maps was used). |
| `confirm` / `no_confirm` | `pattern` | A confirmation card matching `where\|CATEGORY\|summary` was, or was not, shown. `no_confirm` without a pattern means no card at all. |
| `judge` | `rule` | A blind judge, 0 to 1, from the reply, the screenshots and the recorded facts. |

Guidelines: prefer `device` items to `reply` regexes when the result is visible in settings or a content provider; keep regexes tolerant of formatting (`8,?848`); give every task that changes the device a `cleanup`; write a `judge` rule for anything a regex cannot judge, and say exactly what scores full and zero. With `--snapshot`, every task starts from the same emulator snapshot with the clock set to Seoul time, and Chrome is reset either way, so a task must not rely on leftovers from another.

```bash
python bench/run.py --suite v2 --snapshot NAME --no-video   # the whole suite
python bench/run.py --suite v2 --snapshot NAME <id> ...     # chosen tasks
python bench/score.py packets RUN_DIR... --out JUDGE_DIR    # blind packets for the judges
python bench/score.py report RUN_DIR... --judge JUDGE_DIR --out report.md
```

The runner needs a debug build with a signed-in model, the accessibility service on, and an emulator snapshot saved with `adb emu avd snapshot save NAME`. Results go to `bench/results/<timestamp>/`, which Git ignores; finished reports are kept in `bench/reports/`. A full run uses real model requests, so it counts against your plan or API bill.

`judge` items need a person or a judge model. After `packets`, the judge writes `JUDGE_DIR/scores.json` in the format described at the top of `bench/score.py`; without `--judge`, those items stay unscored.

## Text and languages

User-facing text is English by default and Korean on Korean devices. Write it in place with `tr(en, ko)` from `com.phoneuse.i18n`:

```kotlin
Text(tr("Turn on the accessibility service", "접근성 서비스를 켜 주세요"))
```

Both languages stay next to each other, and plain JVM code (routines, cards, nudges) can use it without a `Context`. Do not add text in only one language. If you cannot write Korean, put your best attempt in and say so in the pull request. The agent's prompt is in English and tells the model to answer in the user's language.

The READMEs come in English (`README.md`), Korean, Japanese and Simplified Chinese; keep their structure and facts in sync when you change one. If you can only write some of them, say which ones still need the change. Korean-UI screenshots live in `docs/media/ko/`, English-UI ones in `docs/media/en/` with the same file names.

## Debug tools (debug builds only)

The accessibility service registers a receiver in debug builds only, once the service is connected, so the switch in Settings has to be on. Only senders that hold `DUMP` can reach it, which in practice means `adb shell`. It lets you drive the app from a terminal while developing:

```bash
adb shell am broadcast -a com.phoneuse.DEBUG_DUMP                                    # log the compressed screen tree
adb shell am broadcast -a com.phoneuse.DEBUG_TAP --ei index 3                        # tap element [3]
adb shell am broadcast -a com.phoneuse.DEBUG_TAP --es text "540,1200"                 # tap a point
adb shell am broadcast -a com.phoneuse.DEBUG_SHOT                                    # save /sdcard/Android/data/com.phoneuse/files/pu_shot.jpg
adb shell am broadcast -a com.phoneuse.DEBUG_OVERLAY --es mode show --es text "a|b"  # overlay with lines a, b
adb shell am broadcast -a com.phoneuse.DEBUG_OVERLAY --es mode confirm --es text "…" # confirm card
adb shell am broadcast -a com.phoneuse.DEBUG_OVERLAY --es mode hide
```

The overlay also has the modes `think` and `block`. The benchmark runner also uses `DEBUG_RUN` (start a task, with `--es text` or base64 UTF-8 in `--es text64`, so quotes and Korean survive the shell), `DEBUG_MODEL` (`--es text <model id>`), `DEBUG_STOP`, `DEBUG_ANSWER` (answer a waiting confirm card; `--es mode no` declines it) and `DEBUG_PROBE` (for example `--es mode spec-off` to switch speculation off).

## Screenshots and the demo GIF

The images in `docs/media/ko/` and `docs/media/en/` come from the real debug build running on an Android emulator (API 36, 1080×2400), with the device language set to Korean or English. The unfolded shot uses `wm size 1968x2184`.

- **Sample conversation.** The chats in the screenshots are seeded, not real: they were written into `databases/chat.db` of the debug build with `run-as com.phoneuse`.
- **Overlay states.** These come from the debug broadcasts above.
- **UI tour.** It was recorded with `adb shell screenrecord` and turned into a GIF with `ffmpeg` (12 fps, 360 px wide, 128-color palette).
- **Personal details.** Take screenshots signed out or with seeded data, and check them for account names and email addresses before you commit them.

Keep GIFs under about 5 MB so the README loads quickly.

## Commits and pull requests

**Commits.** Write a short imperative summary of what the change does for the user or the code, in plain English, for example `Add update_plan: the agent's own checklist for long tasks` or `Benchmark runner: stop a timed-out run, keep the screen awake`. Add a body when the *why* is not obvious.

**Pull requests.** Fork, branch from `main`, and keep to one focused change per pull request. The [template](.github/pull_request_template.md) asks for what changed and why, how you tested it, and a safety check.

Before you open one:

- [ ] `./gradlew testDebugUnitTest assembleDebug` passes. CI ([ci.yml](.github/workflows/ci.yml)) runs these plus `lintDebug` and `lintRelease` on every push to `main` and every pull request.
- [ ] Every behavior change has a test.
- [ ] Anything a unit test cannot show was tried on a device or emulator, and the pull request says which one.
- [ ] No new path lets the model pay, delete, change security settings or send without the risk gate.
- [ ] Nothing secret or personal is in the diff, the logs or the screenshots.
- [ ] A change users will notice has a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md). Add that heading above the newest version if it is not there.

**Review and merging.** Only the maintainer merges into `main`, and only after CI passes. A pull request that touches the risk gate, the tools, permissions, sign-in, key storage, the build or the workflows gets the `safety-review` label automatically ([labeler.yml](.github/labeler.yml)), and the maintainer reads it line by line, so expect questions on those.

## Releasing (maintainers)

1. Raise `versionCode` (it must always go up) and set `versionName` in `app/build.gradle.kts`.
2. Update the version in `CITATION.cff` (with `date-released`) and in `docs/index.html` (`softwareVersion` and the facts line under the download button). Set `<lastmod>` in `docs/sitemap.xml` if the page changed.
3. Rename `## [Unreleased]` in `CHANGELOG.md` to the new version with the date, and add its link at the bottom.
4. In all four READMEs, update the test badge if the count changed (tests minus skipped, from the report in `app/build/test-results/testDebugUnitTest/`) and the download size if it moved.
5. Push these changes to `main`, then run the **Release** workflow (Actions → Release → Run workflow, or `gh workflow run release.yml --ref main`). It runs the unit tests and release lint, builds and signs the APK with the key in the repository secrets, checks that the certificate's SHA-256 is `975d08ddf64b789916a15d1ee5aeb6088a8aa176640f90524e9bd16fa8bec8c6` and the version matches, and creates a draft release `vX.Y.Z` with `OpenPhoneUse.apk`, `OpenPhoneUse.apk.sha256` and this version's CHANGELOG section as notes.
6. Read the draft on the Releases page and publish it. Publishing creates the tag. **Keep the file names:** the README's download button and QR code point to `releases/latest/download/OpenPhoneUse.apk`, and a versioned file name would break both.

   The workflow needs four repository secrets: `RELEASE_KEYSTORE_BASE64` (the `.jks` file, base64), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. The build reads the same values from `RELEASE_*` environment variables, or from `~/.openphoneuse/keystore.properties` on the maintainer's machine.
7. Without the workflow, build and publish by hand:

   ```bash
   ./gradlew testDebugUnitTest assembleRelease
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   cp app/build/outputs/apk/release/app-release.apk OpenPhoneUse.apk
   sha256sum OpenPhoneUse.apk > OpenPhoneUse.apk.sha256
   git tag -a vX.Y.Z -m "OpenPhoneUse X.Y.Z"
   git push origin refs/tags/vX.Y.Z
   gh release create vX.Y.Z --verify-tag --title "OpenPhoneUse vX.Y.Z" --notes-file notes.md --latest OpenPhoneUse.apk OpenPhoneUse.apk.sha256
   ```
8. Either way, publishing starts the "Release check" workflow, which downloads the two files and checks the checksum, the signing certificate and that `versionName` matches the tag. Wait for it to pass; if it fails, fix the release before you announce it.

The release key and its password stay outside the repository; the copy in the repository secrets cannot be read back, so it is not a backup. Losing the key means installed copies can never be updated again, so keep a backup away from the build machine.

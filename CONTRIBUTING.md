# Contributing to OpenPhoneUse

Thanks for your interest! OpenPhoneUse is a Kotlin / Jetpack Compose Android app in which an AI agent operates the phone through Android's AccessibilityService. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first: it walks through one agent run end to end.

The repository is `github.com/joungminsung/openphoneuse`. The Android package id is still `com.phoneuse`, so source lives under `app/src/main/java/com/phoneuse/`.

## Getting started

```bash
./gradlew testDebugUnitTest   # JVM unit tests, no device needed
./gradlew assembleDebug       # debug APK: app/build/outputs/apk/debug/app-debug.apk
```

CI runs these same two commands with JDK 17.

You need JDK 17 or newer and the Android SDK (point to it with `ANDROID_HOME` or `sdk.dir` in `local.properties`). Android Studio's bundled JBR works:

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
```

Unit tests run with `user.language=ko` (see `app/build.gradle.kts`), so existing assertions keep their Korean text.

## Code map

Paths are relative to `app/src/main/java/com/phoneuse/`. Tests mirror the same packages under `app/src/test/`.

| Package | What lives there |
|---|---|
| `agent` | `AgentLoop` (the run loop, speculation), `AgentPrompt`, `ToolExecutor` and the screen tools, `MarkLayout` (Set-of-Mark tags), `RiskJudge` (the inline risk arguments), `ToolModule` / `ToolRegistry` and every tool module, `Helper` (`delegate`), `ScreenJournal`, `ScreenWatch` |
| `service` | The AccessibilityService, `AccessibilityTreeReader`, gestures, `EdgeGuard`, `IdleWaiter` (UI settle), `MarkPainter` (draws the marked screenshot), debug receivers |
| `screen` | `UiNode`, `TreeCompressor` (the text screen the model reads), `ScreenBusy` (loading detection) |
| `model` | `ModelClient` and its providers: `OpenAiResponsesClient` (streaming, reasoning carry), `ClaudeClient` (Anthropic Java SDK), `ChatCompletionsClient` (Grok, Gemini); hosted `web_search` |
| `risk`, `security` | `RiskGuard` (keywords for MONEY, SECURITY, DELETE, SEND, CHANGE; the model's inline `risk` in `agent/RiskJudge` is merged with it), `SecretBox` (Android Keystore) |
| `auth` | ChatGPT plan sign-in (`PlanAuth`), token refresh, API key |
| `app` | `AgentRunner` (starts runs), `ModelProviders` / `ModelBackend` (which provider and model a run uses), settings store |
| `chat`, `memory` | Conversation store and search, long-term memory |
| `phone`, `files` | Contacts, calendar, alarms, SMS, notifications, photos, location, file tools |
| `routine`, `proactive`, `today` | Routines (time, place, device triggers), proactive nudges, the Today summary |
| `connectors`, `mcp` | Google connectors, remote MCP servers |
| `overlay`, `assist` | The overlay while a run works, the side-button assistant |
| `cards`, `ui` | Result cards, Jetpack Compose UI (chat, settings, foldable layouts) |
| `i18n`, `usage` | `Lang` / `tr`, the usage meter |

Outside the app: `bench/` holds the benchmark (`suites/v2.json`, the older `tasks.json`, `run.py`, `score.py`, and reports in `bench/reports/`), `docs/` the architecture note and media.

## Ground rules

- **Tests first.** Agent, tool and screen logic is covered by JVM unit tests that use fakes: `FakeDeviceController`, `FakeUserGate`, a scripted model client, and `InMemoryChatRepository` / `InMemoryMemoryStore`. Add or update a test with every behavior change.
- **Safety is not optional.** Anything that pays, deletes, changes security settings or sends must go through the risk gate (`RiskGuard` keywords plus the model's inline `risk` arguments, merged by `RiskCombine`; `ConfirmGate` / `ConfirmPolicyGate` decide the card). The inline judgment may add risk or clear a weak keyword hit, never clear a strong one. Never let the model type passwords.
- **Text from outside the user is data.** Web pages, notifications and other apps' screens must not be able to trigger unconfirmed actions or write sure memories.
- **No root / ADB / Shizuku paths.** At runtime the app relies only on public Android APIs.
- **Prefer facts over rules.** Give the model facts to judge from, such as `more-below` and "Seen before", rather than hard-coded keyword or turn-count rules.
- **Secrets** go through `SecretBox` (Android Keystore) and are never logged.
- **Match the surrounding code style.** Keep files small and focused, and write comments that explain *why*.

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
5. **Update `ToolSpecsPinTest`.** It pins the names, order, descriptions and schemas the model sees. Add your tool names to `BARE_NAMES` / `FULL_NAMES` and replace `BARE_HASH` / `FULL_HASH` with the new values the failing test prints. Only change these on purpose.

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
| `allow_confirm` | no | `true` lets the runner accept confirmation cards; leave it out when the task must not confirm anything. |
| `confirm` | no | `"decline"` for safety tasks: the runner says no to every card and records it. |
| `evidence` | no | Steps run after the reply, as lists of `adb shell` commands and `"shot"` (save a screenshot for the judge). |
| `facts` | no | `adb shell` commands whose output is recorded for the judge (for example the Android version). |

Each rubric item has an `id`, `points` and a `kind`:

| Kind | Fields | Scored by |
|---|---|---|
| `device` | `cmd`, `expect` | An `adb shell` command run after the task; `expect` is a regex over its output. |
| `reply` | `pattern` | A regex over the final answer. |
| `reply_any` | `patterns` | The item's points times the share of patterns that match. |
| `trace` | `pattern` | A regex over the run's tool log (for example that Google Maps was used). |
| `confirm` / `no_confirm` | `pattern` | A confirmation card matching `where\|CATEGORY\|summary` was, or was not, shown. |
| `judge` | `rule` | A blind judge, 0 to 1, from the reply, the screenshots and the recorded facts. |

Guidelines: prefer `device` items to `reply` regexes when the result is visible in settings or a content provider; keep regexes tolerant of formatting (`8,?848`); give every task that changes the device a `cleanup`; write a `judge` rule for anything a regex cannot judge, and say exactly what scores full and zero. Every run starts from the same emulator snapshot with the clock set to Seoul time and Chrome reset, so a task must not rely on leftovers from another.

```bash
python bench/run.py --suite v2 --snapshot NAME --no-video   # the whole suite
python bench/run.py --suite v2 --snapshot NAME <id> ...     # chosen tasks
python bench/score.py packets RUN_DIR... --out JUDGE_DIR    # blind packets for the judges
python bench/score.py report RUN_DIR... --judge JUDGE_DIR --out report.md
```

The runner needs a debug build with a signed-in model, the accessibility service on, and an emulator snapshot saved with `adb emu avd snapshot save NAME`. Results go to `bench/results/<timestamp>/`; finished reports are kept in `bench/reports/`.

## Text and languages

User-facing text is English by default and Korean on Korean devices. Write it in place with `tr(en, ko)` from `com.phoneuse.i18n`:

```kotlin
Text(tr("Turn on the accessibility service", "접근성 서비스를 켜 주세요"))
```

Both languages stay next to each other, and plain JVM code (routines, cards, nudges) can use it without a `Context`. Do not add text in only one language. The agent's prompt is in English and tells the model to answer in the user's language.

The READMEs come in English (`README.md`), Korean, Japanese and Simplified Chinese; keep their structure and facts in sync when you change one. Korean-UI screenshots live in `docs/media/ko/`, English-UI ones in `docs/media/en/` with the same file names.

## Debug tools (debug builds only)

The accessibility service registers a receiver in debug builds only. Only senders that hold `DUMP` can reach it, which in practice means `adb shell`. It lets you drive the app from a terminal while developing:

```bash
adb shell am broadcast -a com.phoneuse.DEBUG_DUMP                                    # log the compressed screen tree
adb shell am broadcast -a com.phoneuse.DEBUG_TAP --ei index 3                        # tap element [3]
adb shell am broadcast -a com.phoneuse.DEBUG_SHOT                                    # save a screenshot to the app's files dir
adb shell am broadcast -a com.phoneuse.DEBUG_OVERLAY --es mode show --es text "a|b"  # overlay with lines a, b
adb shell am broadcast -a com.phoneuse.DEBUG_OVERLAY --es mode confirm --es text "…" # confirm card
adb shell am broadcast -a com.phoneuse.DEBUG_OVERLAY --es mode hide
```

The benchmark runner also uses `DEBUG_RUN` (start a task), `DEBUG_STOP`, `DEBUG_ANSWER` (answer a waiting confirm card; `--es mode no` declines it) and `DEBUG_PROBE` (for example `--es mode spec-off` to switch speculation off).

## Screenshots and the demo GIF

The images in `docs/media/ko/` and `docs/media/en/` come from the real debug build running on an Android emulator (API 36, 1080×2400), with the device language set to Korean or English. The unfolded shot uses `wm size 1968x2184`.

- **Sample conversation.** The chats in the screenshots are seeded, not real: they were written into `databases/chat.db` of the debug build with `run-as com.phoneuse`.
- **Overlay states.** These come from the debug broadcasts above.
- **UI tour.** It was recorded with `adb shell screenrecord` and turned into a GIF with `ffmpeg` (12 fps, 360 px wide, 128-color palette).

Keep GIFs under about 5 MB so the README loads quickly.

## Commits and pull requests

- **Commit messages:** a short imperative summary of what the change does for the user or the code, in plain English, for example `Add update_plan: the agent's own checklist for long tasks` or `Benchmark runner: stop a timed-out run, keep the screen awake`. Add a body when the *why* is not obvious.
- One focused change per pull request. Fork and branch from `main`.
- Describe what changed and why, and how you tested it (unit tests, device, benchmark tasks).
- Make sure `./gradlew testDebugUnitTest` passes. CI (`.github/workflows/ci.yml`) runs `./gradlew testDebugUnitTest assembleDebug` on every push to `main` and every pull request.
- For a change users will notice, add a line under the newest heading of [CHANGELOG.md](CHANGELOG.md).

# Changelog

All notable changes to OpenPhoneUse are listed here, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The Android package id is `com.phoneuse`.

## [Unreleased]

## [0.0.6] - 2026-11-13

- **No texts, calls, call log or contacts permissions.** Galaxy phones refused to install the app as a suspected phishing app ("악성 앱으로 의심"), and those permissions next to accessibility are what phishing apps ask for. The APK now leaves them out. Calls, texts and contacts go through the phone's own Phone, Messages and Contacts apps on screen, and the `call`, `send_sms`, `sms_search`, `find_contact`, `recent_calls` and `add_contact` tools are offered only in a build that declares those permissions.

## [0.0.5] - 2026-11-13

- **An even smaller download.** The APK is about 6 MB. R8 now also shrinks the Anthropic SDK, using the rules the SDK ships for it; Claude requests, streamed replies, thinking and tool calls were checked on a shrunk build.

## [0.0.4] - 2026-11-12

- **A smaller download.** The APK is about 13 MB instead of 75 MB. Release builds are now shrunk with R8 and their code is stored compressed. Nothing changes in what the app does.

## [0.0.3] - 2026-11-11

- **The default model follows new releases.** The model sheet has an "Auto · always the newest" choice at the top. While it is on, the default moves to the highest-numbered model of the same family in your provider's own list (for example GPT-6.1 Terra to GPT-6.2 Terra), and the lighter model used for routine steps follows the same way. Tiers, previews and dated snapshots never replace one another. Picking a model by hand keeps it until you choose Auto again. With an OpenAI key, the list now shows only chat models.
- **Updated libraries.** Jetpack Compose, the Android Gradle Plugin 9.5, Kotlin 2.4.21, OkHttp 5 and the Anthropic SDK 3.6 are updated, and the app now compiles against Android 37. It still targets Android 36, so nothing changes in how it behaves on your phone.

## [0.0.2] - 2026-11-11

- **A calmer look.** The side-button sheet rises from the bottom with a plain shadow instead of a blue glow, shows three suggestions for the screen, and has one button that is the mic while the box is empty and send once you type. "Continue" is a text toggle at the top. The six home-screen widgets are redrawn, and the widget list now shows what each one looks like. Accents are down to one orange: the blue edge glow, purple memory and security cards, amber payment cards and green checks are gone. The other screens are unchanged.
- **The basics, done directly.** Calls, texts, alarms and timers no longer go through the screen. A new `call` tool calls a number or a contact by name. `send_sms` takes a name too and no longer shows a draft card before its confirmation. `set_alarm` takes repeat days, and a new `set_timer` starts a countdown. Each has its own confirmation card; a call, alarm or timer you asked for in your own message goes ahead without it, and so does a contact whose name and number you typed yourself. More direct tools: `flashlight`, `set_volume` (with ringer mode), `media_control`, `device_status`, `recent_calls` and `add_contact`. The app asks for the phone-call, call-log and write-contacts permissions when these are first used, and silent mode needs Do Not Disturb access. An alarm or timer the app could not check afterwards is reported as requested, not as set.
- **Tidying a large folder.** A new `move_files` tool moves every file matching a name pattern in one call, `list_dir` pages through long folders and counts what they hold by type, and the agent now sorts a folder by kind instead of asking for a scope. Before, a Download folder with hundreds of files ended in "please narrow it down". File steps also show plain words instead of tool names.
- **Back to the chat after a task.** A run that worked inside another app now returns to the chat when it ends, unless media is playing or a call is active. Before, a finished game, a filled form or a photo could leave you in that app.
- **What leaves the phone, stated correctly.** The READMEs and landing page now list what a task can send to the model provider and to OpenStreetMap, and the routine map credits OpenStreetMap.
- **Open-source licences in the app.** Settings → About lists every bundled library and its licence, generated at build time. The sign-in notice now says routines can use your plan in the background. The install notes explain the Google Play Protect warnings.

## [0.0.1] - 2026-11-04

First release.

- **Operates the phone like a person.** The agent reads each screen through Android's accessibility service, as an element list and a numbered screenshot, then taps, types, scrolls and moves between apps. It runs on the phone, with no PC, ADB or root.
- **Your choice of model.** Sign in with a ChatGPT plan, or use an OpenAI, Claude, Grok or Gemini API key. GPT-6.1 Terra with fast mode is the default.
- **Asks before it matters.** Money and security actions always ask first, and bulk or permanent deletes do too. The model never types passwords, and keys stay in the Android Keystore.
- **A full chat app.** Memory, web search with sources, voice input, routines, a Today brief, home-screen widgets and notifications that can act.
- **Plan limits handled honestly.** A ChatGPT plan sign-in reports no usage data, so the app links to "Manage usage" in ChatGPT settings. After a usage limit, automatic requests stop for an hour while what you type still goes through.
- **Benchmark suite** in `bench/`, with graded rubrics, blind judge packets and a score report.

Anyone with an earlier test build must uninstall it first: those were signed with a different key and carry a higher version code.

[0.0.3]: https://github.com/joungminsung/openphoneuse/releases/tag/v0.0.3
[0.0.2]: https://github.com/joungminsung/openphoneuse/releases/tag/v0.0.2
[0.0.1]: https://github.com/joungminsung/openphoneuse/releases/tag/v0.0.1

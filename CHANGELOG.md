# Changelog

All notable changes to OpenPhoneUse are listed here, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The Android package id is `com.phoneuse`.

## [0.0.1] - 2026-11-04

First release.

- **Operates the phone like a person.** The agent reads each screen through Android's accessibility service, as an element list and a numbered screenshot, then taps, types, scrolls and moves between apps. It runs on the phone, with no PC, ADB or root.
- **Your choice of model.** Sign in with a ChatGPT plan, or use an OpenAI, Claude, Grok or Gemini API key. GPT-6.1 Terra with fast mode is the default.
- **Asks before it matters.** Money and security actions always ask first, and bulk or permanent deletes do too. The model never types passwords, and keys stay in the Android Keystore.
- **A full chat app.** Memory, web search with sources, voice input, routines, a Today brief, home-screen widgets and notifications that can act.
- **Plan limits handled honestly.** A ChatGPT plan sign-in reports no usage data, so the app links to "Manage usage" in ChatGPT settings. After a usage limit, automatic requests stop for an hour while what you type still goes through.
- **Benchmark suite** in `bench/`, with graded rubrics, blind judge packets and a score report.

Anyone with an earlier test build must uninstall it first: those were signed with a different key and carry a higher version code.

[0.0.1]: https://github.com/joungminsung/openphoneuse/releases/tag/v0.0.1

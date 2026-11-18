# HerdrDroid 2.0 voice and responsiveness

This ExecPlan tracks progress, discoveries, decisions and release results.

## Purpose

The existing mic still dictates into the composer. Voice Control adds explicit commands. A separately enabled hands-free session listens for “Hey Herdr” while the Pixel is locked and speaks through normal Bluetooth media routing. Pane switches show the correct chat promptly and question answers get immediate feedback.

## Progress

- [x] Confirmed the existing transcript, dictation, question and signed release paths.
- [x] Implement voice controls, responsive question actions, drawer model labels, and a send menu for queued drafts.
- [ ] Test the final combined branch after review.
- [ ] Document the release, publish a signed APK and ask Claude to pull the repo on X.

## Surprises & Discoveries

Telegram Auto Bridge delegates its audio to Android Auto. HerdrDroid needs its own local listening path for plain Bluetooth. Phone and EX30 testing will follow the build.

## Decision Log

Keep explicit session start, local recognition and manual tool approvals. Reuse the existing pane and question send paths. Keep the current signing key and application ID so the APK can upgrade the installed app.

## Plan of Work

Add voice parsing and a microphone foreground service in the voice package. Wire the composer mic and MainActivity to existing navigation, transcript and question operations. Improve transcript and answer responsiveness in AppViewModel and AgentScreen. Update the README and version only after the feature is ready for the signed test build.

## Validation and Acceptance

Run testDebugUnitTest in Docker on minipc. Review changes and verify the APK signature against 1.4.11. Use the GitHub release workflow for the APK. On the Pixel, check upgrade, dictation, pane switches, single-tap answers and voice disabled. Then pair Bluetooth while parked, start hands-free with the app visible, lock the phone, and verify wake, command, readout and Stop. The hardware result remains pending until Bill runs that test.

## Recovery and Ownership

Voice is opt-in. Stop the hands-free session from its notification to release the microphone. An ambiguous or stale question must never receive a retry automatically. Use card herdrdroid-2-voice for handoff and evidence.

## Outcomes & Retrospective

The voice and question build passed the JVM suite and signed APK checks on minipc. A synthetic Vosk probe recognized the wake phrase, a read command and a restricted confirmation phrase. The final Steer menu build, Grok review and Pixel/Volvo validation are pending.

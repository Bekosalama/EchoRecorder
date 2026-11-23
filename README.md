# Echo Recorder — Android MVP 0.5

Arabic-first Android audio recorder built with Kotlin and Jetpack Compose.

## Implemented in this development build
- Arabic interface
- User-started microphone recording using AAC audio in an M4A container
- Foreground microphone service: recording can continue when the app is backgrounded; a persistent notification includes a stop-and-save action
- Private app storage for recordings
- Playback, pause, search, rename, share, and delete with confirmation
- Optional call-state alerts for ringing, active, and ended states
- Recording state and start timestamp persisted while the app/service remains running
- Live elapsed-time display while recording
- Recording file duration shown in the recordings list
- Playback resumes from pause when the same file is selected again

## Important limits
- This is a **voice recorder**, not a phone-call audio recorder. It records the microphone and does not capture the remote caller's audio.
- Call-state alerts are notifications only. Automatic phone-call recording is not implemented because Android restricts call-audio capture and background microphone behavior varies by OS, device, region, and policy. Do not assume the app can record both call participants.
- Recording requires the user to start it from the app and requires microphone permission. An ongoing notification is used while recording.
- This source update has not been built with Gradle or tested on a physical phone in this environment.

## Build in Android Studio
1. Open the `EchoRecorder` folder in Android Studio.
2. Allow Gradle sync and install required SDK components.
3. Run on a physical Android device (recommended for microphone/service behavior).
4. Grant microphone permission when asked.

## Project details
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21
- Compile SDK: 35
- Target SDK: 35
- Minimum SDK: 26 (Android 8.0)
- Version: 0.5.0

## 0.5 development notes
- Added elapsed timer to the active recording card.
- Added media duration to saved recording cards.
- Play button resumes a paused file instead of restarting it.
- New recordings are written to a hidden `.partial` file and renamed to `.m4a` only after a successful stop, so incomplete files do not appear in the library.
- Old partial files (older than 24 hours) are cleaned up when the recording service starts.
- Library header displays aggregate storage used by saved recordings.
- Service teardown attempts to finalize the active clip rather than leaving an incomplete file.
- Updated Android version metadata to 0.4.0 (versionCode 4).

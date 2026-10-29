# smb-browser

Read-only Android SMB2/3 browser. Kotlin + Jetpack Compose, single `:app` module, minSdk 26.

## Build

- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Needs JDK 21 and an Android SDK (`ANDROID_HOME` or `local.properties`).
- CI (`.github/workflows/build.yml`) builds the debug APK on every PR and uploads it unzipped.
- Every push to `main` runs `.github/workflows/release.yml`, which publishes the APK as a GitHub pre-release `v0.0.<run>`. The stakeholder installs these through Obtainium, so a merge = an update on his phone.

## Project docs

- The plan, decision log and handoff are private and live outside this repo. The stakeholder gives access at the start of each thread (vault or `Notes` repo).
- Update HANDOFF and DECISIONS there before ending every thread. Never write them into the gitignored `documentation/` folder; it is not the source of truth.

## Conventions

- Versions are pinned inline in the Gradle files. No version catalog until there are enough deps to justify one.
- SMB via smbj (Apache-2.0). Share listing needs smbj-rpc (BSD-3).
- `app/debug.keystore` is committed on purpose so CI builds install over each other. Never commit a release key.
- One branch + PR per task. PR descriptions are for a non-developer stakeholder: what changed, how to test on a phone, known gaps. No code walkthroughs.
- Run the `reviewer` agent on the diff before opening a PR.

## Don't

- Add write operations (upload, rename, delete, move, mkdir). The app is read-only in v1.
- Add SMB1 support or network discovery.
- Copy code from Material Files (GPL). Reading it for reference is fine.
- Commit credentials, hostnames, IPs or anything else about the stakeholder's network. The repo is public.

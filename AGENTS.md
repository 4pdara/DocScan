# DocScan project instructions

- Communicate with the owner in Hebrew. Inspect the current code, Git status and repository documentation before editing; preserve existing work and stay within the requested milestone.
- The owner has explicitly authorized uploading ongoing project code and documentation to `4pdara/DocScan` on GitHub. Do not ask again merely to push this project's work. Follow the requested branch, validation, merge and release workflow; this authorization does not cover unrelated repositories, secrets or private reference binaries.
- Validate relevant changes with `testDebugUnitTest`, `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest`, then verify the Android emulator checks in GitHub Actions. Review the diff and update documentation before committing. Use a clean build when diagnosing stale incremental compiler output.
- Do not publish proprietary reference APK/APKS/APKM files, extracted code, models or uploaded videos. Keep their analysis outside the repository; commit only findings and original integrations through documented public APIs.
- Distinguish verified results, device-testing limitations and planned features. A successful build is not evidence of real-camera quality or support for every Google Play services configuration.

# RoQuiz

Offline-first Android study and quiz app. The Android APK embeds its study interface and local parsing engines; no network connection is needed while studying.

## Build

Run the **Build RoQuiz APK** workflow in GitHub Actions. Download the `RoQuiz-debug-apk` artifact from the completed run.

## Offline support

PDF, PPTX and image text extraction runs on-device. OCR language data and parsing libraries are packaged in the APK during the build. No runtime API or cloud service is used.

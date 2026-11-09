# Expense Scanner (Android)

Snap a bill, tap where it was spent, and the phone reads the rest: vendor, date, items,
GST/HST and other taxes, tip, total, card, receipt number and the vendor's GST/HST number.
Everything runs **on the phone**:

- **Google ML Kit** reads the printed text (offline).
- **Gemma 4 E2B** (Google's open model, run with LiteRT-LM) sorts the text into fields. Its
  answer is constrained to a JSON schema, then every amount is checked against the printed text
  and against a rule-based reader; anything that doesn't add up is flagged.
- The model is a one-time 2.6 GB download from Hugging Face (`litert-community/gemma-4-E2B-it-litert-lm`).
  Google lists it for phones with 8 GB of RAM or more. Until it's downloaded, bills are read by
  the rule-based reader.

Exports: an Excel workbook (Summary with live formulas, Expenses, Line items and, optionally,
the receipt photos embedded) and a .zip of the photos. Save either to the phone, send it to
Google Drive, or share it to any app.

## Install

Every push to `main` builds a signed APK on GitHub Actions and publishes it under **Releases**.
Open the latest release on the phone, download the `.apk`, and allow your browser to install apps
when Android asks. Newer builds install over older ones and keep your bills.

## Build it yourself

Android Studio (JDK 17+) or `./gradlew assembleRelease`. Toolchain mirrors Google's AI Edge
Gallery app: AGP 9.0.1, Gradle 9.2.1, Kotlin 2.2.21 with `-Xskip-metadata-version-check`,
`com.google.ai.edge.litertlm:litertlm-android:0.18.1`.

The receipt logic (rule-based reader, model-output checks, Excel writer) lives in
`app/src/main/java/com/expensescanner/app/core` and has no Android dependencies.

## Signing key

`keystore/` holds the key that signs every build, so updates install over the previous version.
Keep this repository private. If you lose the key, a new build can't update the installed app
(you'd export your bills, uninstall, and reinstall).

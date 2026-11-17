# Development

## Local setup

Install JDK 17 and Android SDK Platform 35. Set Android Studio's Gradle JDK to 17 if you build from the IDE. The Gradle wrapper is included.

Set `ANDROID_HOME` to your Android SDK directory or create a root-level `local.properties` file with `sdk.dir=/path/to/Android/Sdk`. Use `gradlew.bat` in place of `./gradlew` on Windows.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The [GitHub Actions workflow](../.github/workflows/android.yml) runs the same checks and uploads the APK as a workflow artifact. Use a stable release signing key for publicly distributed release builds so future versions can update them in place.

## Source layout

The app is a single Android module built with Kotlin, Jetpack Compose, and Material 3. Source files are under [`app/src/main/java/it/linko/app`](../app/src/main/java/it/linko/app).

| Area | Responsibility |
| --- | --- |
| `MainActivity.kt` and `LinkoDrafts.kt` | Android lifecycle, incoming share intents, and in-memory paste drafts. |
| `ui/` | Compose screens for browsing, filtering, reviewing imports, and configuring desktop access. |
| `data/` | SQLite persistence, observable library state, URL and metadata extraction, category rules, filters, and smart collections. |
| `server/` | Android foreground service, HTTP authentication and browser pages, and optional editing against the same library. |

Linko reads metadata with Jsoup and loads thumbnails with Coil. The Android UI observes the repository's `StateFlow`. The HTTP server reads the same library and delegates permitted writes to the repository.

## Test on a device

JVM tests cover URL extraction, metadata parsing, filters, category rules, smart collections, and the HTTP server. For changes that cross Android or browser boundaries, also verify the relevant flows on a device or emulator.

- Share a URL to Linko, paste a batch with a duplicate, and save. Restart the app and check that the library persists.
- Export a JSON backup, import it into a test library, and check that categories, links, and rules are restored.
- Start sharing with a single category selected. Open the browser, sign in, and confirm that only the selected category's links are visible.
- Confirm that read-only mode prevents browser edits. Restart sharing with editing enabled, save a browser edit, and check it on the phone.
- Stop the foreground service from its notification and confirm that the browser can no longer load the library.

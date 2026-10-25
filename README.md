# Tiresias

Named for the prophet who warns Odysseus of the dangers ahead: Tiresias tells you what will break the firmware build before cangen does.

A single-file Java Swing tool for browsing, searching, editing and validating the Calypso/CANGEN JSON spec files. No dependencies; needs Java 21 or newer (the bundles include their own Java 21 runtime).

## Download

Get the latest version from the repository's **Releases** page. Pick the file for your computer: `macos-aarch64` for Apple Silicon Macs, `windows-x64` or `windows-aarch64` for Windows, `linux-x64` for Linux. Each includes its own Java runtime. If you already have Java 21+, the plain `.jar` runs anywhere with `java -jar`.

## Run

```
java -jar Tiresias.jar path/to/spec-folder      # prebuilt jar
java Tiresias.java path/to/spec-folder          # straight from source (JDK 21+)
```

Opening a folder loads every `*.json` message list in it recursively (hidden folders and JSON files that aren't message lists are skipped). The last folder reopens automatically. You can also drop files or folders onto the window.

## Decode CAN traffic

Tools > Decode CAN traffic (Ctrl+Shift+T) opens a window that decodes `candump` output against the loaded spec. Open or drop log files, paste lines, or click Listen live to run `candump` on the car's computer over SSH (using the `ssh` client built into macOS, Linux and Windows 10/11). An SSH key is the most reliable way to log in; set one up once with `ssh-copy-id user@car-host`. It flags unknown IDs, frames shorter or longer than the spec, and standard/extended mismatches, and shows a row per ID with frame counts and rates, or every frame.

From the command line:

```
candump -L can0 | java -jar Tiresias.jar --candump - path/to/spec-folder
java -jar Tiresias.jar --candump log.txt path/to/spec-folder
```

## Themes and animations

View > Theme switches between One Dark (default), Dracula, Nord, One Light and your OS's native look, with a short crossfade. View > Animations turns off the fades, slides and hover effects if you prefer.

The Gradle build bundles FlatLaf into the jar, which gives text fields, menus, dialogs and the file chooser a modern look in the same colors. Run straight from source (`java Tiresias.java`), the app uses its own built-in look instead.

## App icon

The app draws its own icon (rows of colored bit blocks), so the window, Dock and taskbar never show Java's coffee cup, even when run from source. The Gradle build also puts it on the macOS app, the Windows and Linux bundles, and the jpackage app.

To use your own image, put a square PNG named `icon.png` (1024×1024 is ideal) next to `build.gradle` and rebuild. `gradle icons` exports the current icon as `.png`, `.icns` and `.ico` into `build/icons`.

## Startup

The window opens right away and spec files load in the background behind a spinner. The bundles' launchers also keep a class-data archive in your user cache folder, so launches after the first start faster.

## Formatting

The code is formatted with [Prettier](https://prettier.io) and its Java plugin, run through [Spotless](https://github.com/diffplug/spotless). Spotless downloads the pinned Prettier versions itself; it only needs Node.js installed (the app doesn't).

```
gradle spotlessApply   # format everything (or: gradle format)
gradle spotlessCheck   # fail if anything isn't formatted (or: gradle lint); also part of gradle check
```

The settings are in the `spotless` block of `build.gradle`: Java gets 120-column lines and 4-space indents, Markdown and the GitHub workflows get Prettier's defaults, and build files just get whitespace tidied. CAN spec JSON is never touched, so it keeps the jq / format.sh layout the firmware repos use.

## Validate in CI or a pre-commit hook

```
java -jar Tiresias.jar --validate path/to/spec-folder            # exit 1 on errors
java -jar Tiresias.jar --validate path/to/spec-folder --strict   # also fail on warnings
java -jar Tiresias.jar --validate path/to/spec-folder --info     # also print info notes
```

## Build and package with Gradle

Put `build.gradle`, `settings.gradle` and `gradle.properties` next to `Tiresias.java`, then run Gradle with JDK 21 (Gradle 8.3 or newer):

```
gradle jar             # build/libs/Tiresias.jar, runs on Java 21+
gradle bundleAll       # build/dist/: macOS arm64, Windows x64, Windows arm64, Linux x64
gradle appImage        # native .exe / .app for the machine you're on (jpackage)
gradle validateSpec -Pspec=path/to/spec-folder
```

Each bundle includes a trimmed Java runtime (about 50 MB unpacked), so users don't install Java. Any OS can build all four bundles: the first run downloads each platform's Temurin JDK once and caches it in `~/.gradle/caches`. Run `gradle wrapper` once to add `gradlew` scripts so teammates don't need Gradle installed.

The macOS app isn't signed. The first time, right-click it and choose Open, or run `xattr -dr com.apple.quarantine Tiresias.app`.

## Releasing

Push a version tag and GitHub Actions does the rest:

```
git tag v1.2.0
git push origin v1.2.0
```

`.github/workflows/release.yml` builds the jar and every bundle (with version 1.2.0 in their names), then publishes a GitHub Release with them attached, a download table, `SHA256SUMS.txt`, and notes listing the changes since the last release. Tags with a dash (`v1.3.0-beta.1`) become pre-releases. To try a build without publishing anything, use **Run workflow** on the Release workflow's page in the Actions tab; the files appear on that run.

# Tiresias

Named for the prophet who warns Odysseus of the dangers ahead: Tiresias tells you what will break the firmware build before cangen does.

A Java Swing tool for browsing, searching, editing and validating the Calypso/CANGEN JSON spec files. No dependencies; needs Java 21 or newer (the bundles include their own Java 21 runtime).

## Download

Get the latest version from the repository's **Releases** page. On an Apple Silicon Mac, download the `.dmg`, open it and drag Tiresias to Applications. On Windows, pick `windows-x64` (or `windows-aarch64` for ARM); on Linux, `linux-x64`. Each includes its own Java runtime. If you already have Java 21+, the plain `.jar` runs anywhere with `java -jar`.

## Run

```
java -jar Tiresias.jar path/to/spec-folder      # prebuilt jar
./gradlew runApp -Pspec=path/to/spec-folder    # from source (JDK 21+)
```

Opening a folder loads every `*.json` message list in it recursively (hidden folders and JSON files that aren't message lists are skipped). The last folder reopens automatically. You can also drop files or folders onto the window.

## Browse a file

When a folder opens, the files start collapsed in the list on the left and the main area shows every file as a tile, with its message count, ID range and problems. Hover a tile to list its messages; click it to open that file. Hover a message tile to see its points with their sizes and the message's total size, padding included.

Click a file (such as `vcu.json`) in the list on the left to see all of its messages as a grid of tiles: ID, name, size, field count, and a colored edge on messages with problems. Click a tile to open that message. Type in the filter box to narrow the tiles by ID, description, topic or point name, then press Enter to open the first match, or press Down and use the arrow keys and Enter. When a file has more messages than fit, the tiles switch to a compact two-line style so the whole file usually shows at once.

## Reading the editor

The Points tab's **Type** column shows the Rust type firmware-rs generates (`bool`, `u8`, `i16`, `u32`, `f32` and so on). That isn't always the `c_type`: a divide or multiply formatter makes any point an `f32`, `bool` only applies to 1-bit points, and integers are sized from their bits. When the two differ, the column shows the `c_type` beside the type, and its tooltip says why. Picking a type writes the C name to `c_type` (`uint8_t`, `int16_t`, `float`). Values are colored by kind, from the current theme: bit positions cyan, sizes and numbers orange, types by kind (bool green, unsigned blue, signed yellow, floats purple), formatters cyan, little endian yellow, and topics with the node in blue, the last level bright and `{n}` placeholders orange. In the Fields tab, units are green and point references such as `#3` orange. The box under the Fields table edits the selected field's notes (`desc`), or its `doc` while a Doc cell is selected. The JSON tab colors keys blue, strings green, numbers orange and `true`/`false`/`null` purple.

## Find your way around

- **Go to message** (Go menu, Cmd+P, Ctrl+P on Windows and Linux): type part of an ID (`123` or `0x123`), a description, a key, a topic or a file name and press Enter. Every word you type has to match, and letters in order also work (`shtdwn`). With nothing typed it lists the messages you opened recently.
- **Back and Forward** (Cmd+[ and Cmd+] on a Mac, Alt+Left and Alt+Right elsewhere) step through the messages and files you've opened.
- **Topic autocomplete:** while you type a topic in the Fields tab, a list shows the levels other topics already use (`VCU/` offers `VCU/eFuses/`, `VCU/Faults/` and so on, most used first). Down/Up choose, Enter or Tab take one, Escape closes the list; press Down to open it again.
- **Find usages** (Go > Find topic usages, Cmd+Shift+F): every field at or under a topic level across all files, such as everything under `VCU/Faults`; double-click one to open it. Right-click a field row for "Find usages of" any level of its topic, or a point row to see which fields publish that point.
- **Rename a topic level** (Edit > Rename topic level, or right-click a field row): renames `VCU/Faults` to, say, `VCU/Fault` in every topic under it, in every file. The list previews each new topic and warns if one would match a topic another field already publishes. It's one undo step per file.

## Review changes before saving

Saving shows what will change first: each file with unsaved changes, its added and removed lines, and a colored diff against the file on disk. If a file's contents changed on disk after you opened it (a `git pull`, another editor), it says so, since saving would overwrite that change; use File > Reload from disk to keep it instead. Formatting-only differences such as Windows line endings aren't counted as changes, but the review notes when saving changes the line endings or the newline at the end of the file. Untick "Show this before every save" to save straight away; File > Review unsaved changes shows the same view at any time.

## Fix problems

Right-click a problem in the Problems list at the bottom, a point or field row with a problem, or the problem list under a message's header. When Tiresias knows the fix, the menu offers it (for example "Fix: Remove name, signed from this padding point"), plus "Fix all N …" when the spec has more problems of that kind. Each fix is one undo step per file. The fixable problems are:

- padding points with keys besides `size` and `parse`, and messages that aren't byte aligned;
- IDs with an uppercase `0X`, no `0x` at all, or spaces around them, and IDs above 0x7FF without `"is_ext": true`;
- `endianness` on points of 8 bits or fewer, and the outdated `default_value` and `format: "divideN"` keys;
- fields missing `unit` or `doc` (added as `""`), a missing `fields` list, and `bidir_mode` in the wrong case;
- topics with leading, trailing or doubled slashes, `values` that list a point twice, and sim option frequencies that don't add up to 1 (scaled so they do).

**Keep byte aligned** (in the Points tab's toolbar, on by default) keeps messages byte aligned as you edit points. After each points edit it resizes the last padding point, or adds or removes one, so the message is the smallest whole number of bytes that holds its points: growing a point uses up padding (or adds a byte), and shrinking or removing one trims it, so removing the 17th bit of a 32-bit message takes out the 15 padding bits and leaves 16. New points go in front of that padding. Editing the padding point's own size is left alone, and so is JSON you apply in the JSON tab. **Pad to byte** does the same once, on demand.

## Decode CAN traffic

Tools > Decode CAN traffic (Ctrl+Shift+T) opens a window that decodes `candump` output against the loaded spec. Open or drop log files, paste lines, or click Listen live to run `candump` on the car's computer over SSH (using the `ssh` client built into macOS, Linux and Windows 10/11). An SSH key is the most reliable way to log in; set one up once with `ssh-copy-id user@car-host`. It flags unknown IDs, frames shorter or longer than the spec, and standard/extended mismatches, and shows a row per ID with frame counts and rates, or every frame.

The window has two views:

- **Raw frames**: the frames as they came in, with their data bytes, either one row per ID or every frame.
- **Decoded**: one row per ID with every point's latest value by its point name, such as `state 3  temp_average 34 C`. Values that changed since that ID's last frame are highlighted, and 1-bit points show as 1/0 chips. **Last seen** says how long ago the ID's last frame came, and an ID is marked **Stale** when nothing has arrived for three of its usual periods (and at least a second).

Rates are averaged over the gaps between each ID's last 3 to 10 frames, so they follow the current rate rather than the whole log's average. Select a row and open the **Points** tab below for each point's value, raw bits and the range seen so far. Click **Show graph** there to graph the selected point (the graph starts hidden and isn't drawn until you show it); **Show the last** sets how much of it to show (5 s up to 5 min, any length you type such as `20 s` or `2 min`, or All). The graph ends at the newest frame, and while listening live it scrolls with the clock. Hover it to read the value at any moment. **Copy frame** (or Cmd/Ctrl+C in either table) copies the selected frame (the latest one, in a row per ID): its message, each point's decoded value and unit, then its candump line, which you can paste back in. While frames stream in, the Details text keeps its scroll position.

The **eFuses** tab shows the VCU's 11 eFuses laid out like the board (PUMP1, MC, BRAKELIGHT across the top), each with its current, voltage, ON/OFF/FAULT state and the last 30 s of current. An **AUTO** chip marks an eFuse in control state 1, where the VCU switches it on and off itself depending on conditions in its code, so OFF can be normal there; hover a card for all its values, or double-click it to open the message in the editor. The **VCU faults** tab shows the VCU's fault flags (`0x502`): a tile per fault, critical ones first, saying whether it's active, for how long and how many times, next to a list of every change with its time. The **BMS faults** tab does the same for the BMS's Fault Status message (`0x89`). These four tabs (eFuses, Shutdown loop, VCU faults, BMS faults) use the whole window; the frame details below come back on Raw frames and Decoded. The layout is a small table in the code (`EFUSE_LAYOUT` in `src/main/java/tiresias/traffic/dashboards/EfuseBoard.java`) if the board changes. In the Decoded view, IDs are grouped by node (spec file); click a group's header to fold it away.

While listening live:

- **Pause** freezes the display so you can read it. Frames keep arriving in the background and appear on **Resume**.
- **Save log…** writes every frame received so far as a candump `-L` log. The window keeps the newest 300,000 frames (a few minutes of a busy bus), so for long sessions tick **Save every frame to a log file** in the Listen live dialog. That writes every frame to a new file in the folder you pick (`Documents/Tiresias logs` by default) as it arrives.
- If the connection drops after it had been working (a Wi-Fi blip, the car rebooting), it reconnects by itself after 2, 4, 8 and then every 10 seconds, and says how long the gap was; the saved log marks each gap with a `#` comment line. It stops trying on problems that retrying can't fix, like a wrong password.
- **Remember the password on this computer** keeps the SSH password in the macOS Keychain (or the system keyring on Linux, through `secret-tool`), so it's filled in next time. Untick it to remove it. Where there's no keyring (Windows), it's remembered only until you close the app; an SSH key is the better choice there.

The **Shutdown loop** tab shows the loop from the VCU's Shutdown Pins frames (`0x123`). It draws the loop stage by stage and highlights where it's open: each pin reads 1 while the loop is closed up to it, so the break is between the last pin reading 1 and the first reading 0. Stages no pin senses (the side BRBs, say) are included in the highlighted range when the break could be at them. The menu on the right lists every change of state in the log, with its time, so you can see when and where the loop opened.

The loop order lives in one list in the code, `ShutdownLoop.STAGES` in `src/main/java/tiresias/traffic/dashboards/ShutdownLoop.java`, with one line per stage: `pin("TSMS", "tsms_gpio")` for a sensed stage, `unsensed("Side BRB R")` for one without a pin. Reorder or edit the lines when the loop changes. If the spec renames or adds a pin, the panel says so.

From the command line:

```
candump -L can0 | java -jar Tiresias.jar --candump - path/to/spec-folder
java -jar Tiresias.jar --candump log.txt path/to/spec-folder
```

### Try live capture without the car

`tools/FakeCandump.java` prints a realistic candump stream built from the spec: every message at its `sim_freq` period, values from each point's `sim`, the shutdown loop opening at a few stages (always in loop order: an open stage reads 0 on its own pin and every pin after it), the VCU eFuses healthy except for one that faults every 20-50 s (the pumps and fans in auto, switching themselves on and off), a VCU fault and a BMS fault coming on now and then, and the IMD going quiet for 10 s every 40 s so its IDs turn stale. In Listen live, choose **On this computer** and set the command to:

```
java /path/to/Tiresias/tools/FakeCandump.java --iface {iface}
```

It sends about 1,500 frames per second, like a busy bus. `--speed 0.2` slows every message down, `--only bms,vcu` (files or IDs) narrows it, and `--list` shows what it would send. Run it with `--help` for the rest. It's a single Java file with no dependencies, so it runs anywhere Java 21 does.

## Themes and animations

View > Theme switches between One Dark (default), Dracula, Nord, One Light and your OS's native look, with a short crossfade. View > Animations turns off the fades, slides and hover effects if you prefer.

The Gradle build bundles FlatLaf into the jar, which gives text fields, menus, dialogs and the file chooser a modern look in the same colors. Without FlatLaf on the classpath (a plain `javac` build), the app uses its own built-in look instead.

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

The source is in `src/main/java/tiresias/`, one file per class, in folders by area: `spec/` (reading and validating spec files), `editor/` (the main window), `traffic/` (candump decoding and the traffic window, with its dashboards in `traffic/dashboards/`) and `ui/` (theme and shared components). Run Gradle with JDK 21 (Gradle 8.3 or newer):

```
gradle jar             # build/libs/Tiresias.jar, runs on Java 21+
gradle bundleAll       # build/dist/: macOS arm64, Windows x64, Windows arm64, Linux x64
gradle dmg             # build/dist/Tiresias-<version>-macos-aarch64.dmg (on a Mac, with: pip install dmgbuild==1.6.7)
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

`.github/workflows/release.yml` builds the jar and every bundle (with version 1.2.0 in their names), wraps the Mac app in a drag-to-Applications disk image (on a macOS runner, using `packaging/`), and publishes a GitHub Release with everything attached, a download table, `SHA256SUMS.txt`, and notes listing the changes since the last release. Tags with a dash (`v1.3.0-beta.1`) become pre-releases. To try a build without publishing anything, use **Run workflow** on the Release workflow's page in the Actions tab; the files appear on that run.

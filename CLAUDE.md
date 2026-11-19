# Tiresias

A personal project, not an official team tool. A Java 21 Swing desktop app for browsing, editing and validating the CAN spec JSON files of NER (Northeastern Electric Racing): the Calypso / Odyssey-Definitions message lists that firmware-rs `cangen` turns into Rust. It also decodes candump traffic against the spec, live over SSH from the car. Named for the prophet who warns Odysseus of dangers ahead: it reports what will break the firmware build before cangen does.

## Repository layout

- `src/main/java/tiresias/`: the app, one file per class, in a package per area (the entry point is `tiresias.Tiresias`):
  - `tiresias/`: `Tiresias` (entry point), `Cli` (`--validate`, `--candump`), `Settings` (preferences).
  - `spec/`: the spec files and their rules: `Json`, `JsonException`, `Spec` (helpers), `Loader`, `Validator`, `Fixes`, `Topics`, `Diff`, `Families`.
  - `editor/`: the main window: `App`, `MessageEditor`, `IssuesPanel`, `MessageGrid`, `FileGrid`, `BitLayoutPanel`, `GoToDialog`, `TopicsDialog`, `ReviewDialog`, `FileNode`, `MsgNode`.
  - `traffic/`: candump decoding and the traffic window: `Can` (parsing and decoding), `TrafficWindow`, `PointGraph`, `Keychain`.
  - `traffic/dashboards/`: the whole-window traffic pages: `EfuseBoard`, `FaultsPanel`, `ShutdownLoop` (the loop model), `ShutdownPanel`, `LoopDiagram`.
  - `ui/`: shared look and components: `Theme`, `Ui`, `Look`, `Widgets`, `Suggest`, `JsonPane`, `Icons`, `LoadingOverlay`, `FullRowTree`, `TreeRow`, `TileGrid`.
- `build.gradle` (the app version, default `1.1.0`, overridden by `-PreleaseVersion` on release builds; `jdkFeature` 21 and `runtimeModules` `java.desktop,java.prefs,java.logging`, each overridable with `-P`), `settings.gradle` (`rootProject.name = 'Tiresias'`), Gradle wrapper (`gradlew`, `gradle/wrapper/`).
- `packaging/`: `DmgBackground.java` (draws the disk image background; `gradle dmg` runs it into `build/dmg-art`, so the PNGs aren't kept) and `dmg-settings.py` (dmgbuild layout for the macOS drag-to-Applications disk image: 660×400 window, app at (165, 190), Applications at (495, 190)).
- `.github/workflows/lint.yml`: `gradle spotlessCheck` on every push and pull request (Java 21, Node 22, Gradle 8.14).
- `.github/workflows/release.yml`: on a `v*` tag, three jobs: `build` (all bundles on Linux), `dmg` (the `.dmg` on `macos-15` from that macOS bundle, dmgbuild 1.6.7; GitHub retired `macos-14` in November 2026) and `publish` (checksums, then `gh release create`). "Run workflow" makes test builds versioned `0.0.<run number>` without publishing.
- `README.md` (user docs), `.gitignore`, `LICENSE` (MIT), optional `icon.png` (custom app icon; the build uses it when present).
- `tools/FakeCandump.java`: a fake candump for testing Listen live without the car (On this computer, command `java tools/FakeCandump.java --iface {iface}`; a single file with its own small JSON reader, no dependencies). It encodes values from each point's `sim` the same way the decoder reads them; `--duration N --fast` writes N simulated seconds at once for offline checks.
- `Odyssey-Definitions/`: git submodule with the team's real spec (`can-messages/`), used for testing.

## Commands

```
./gradlew jar                                  # build/libs/Tiresias.jar (FlatLaf merged in)
./gradlew runApp -Pspec=path/to/spec-folder    # run the GUI
./gradlew validateSpec -Pspec=folder [-Pstrict]
java -jar build/libs/Tiresias.jar --validate folder [--strict] [--info]   # exit 1 on errors
java -jar build/libs/Tiresias.jar --candump log.txt folder                # decode a candump log; "-" reads stdin
./gradlew bundleAll          # build/dist/: macos-aarch64, windows-x64, windows-aarch64, linux-x64 (cross-built)
./gradlew bundleMacosAarch64 # one platform (also bundleWindowsX64, bundleWindowsAarch64, bundleLinuxX64)
./gradlew dmg                # build/dist/Tiresias-<v>-macos-aarch64.dmg from that bundle; macOS only, needs dmgbuild 1.6.7 (2.x needs `diskutil image`, which older macOS lacks); -PmacBundle=<tar.gz> reuses a bundle, -Pdmgbuild=<path>
./gradlew appImage [-Pverbose]   # native .app/.exe via jpackage for the host OS
./gradlew icons              # build/icons: icon.png, icon.icns, icon.ico
./gradlew spotlessApply      # format (alias: format); spotlessCheck checks (alias: lint, part of check)
```

Other command-line flags: `--export-icons <dir> [source.png]` and `--askpass` (the SSH_ASKPASS helper used by live capture).

## Code map (files in src/main/java/tiresias)

Big classes have their own file. Classes and members are `public` so the packages can use each other; every file imports all the app's packages (`import tiresias.spec.*;` and so on). Small shared helpers and types sit in a few holder classes that every file pulls in with `import static`, such as `import static tiresias.spec.Spec.*;`, so they're used by simple name: `Spec` (JSON-tree and spec helpers: `asMap`, `strOr`, `hex`, `parseId`, `offsets`, Rust naming, plus `SpecFile`, `Issue`, `Severity`, `SpecRef`), `Can` (CAN traffic, below), `Families`, `Ui` (the theme `T`, color math, `aa`, `mono`, `button`, `styleTable`, `escHtml`, animations), `Look` (the Swing UI delegates `FlatButtonUI` and the rest, borders, check and chevron icons) and `Widgets` (`Surface`, `Bar`, `ToneLabel`, `HintField`, `StyledRenderer`, `TabsWithTrailing`, `FadeUI`, `SevIcon`). Put a new helper in the holder it fits; give a new class of more than a few dozen lines its own file, in the package of its area, and declare what other packages use as `public`.

- **`Tiresias`** (entry and startup): `main` handles the command-line flags, then starts font discovery, Swing class preloading and spec loading in parallel while the window builds.
- **`Json`:** an order-preserving parser and writer that keeps numbers as `BigDecimal`. The JSON tree is the source of truth, so unknown keys and key order survive a round trip; the editor writes edits back into it.
- **`Loader`:** parallel file loading off the UI thread, then family detection, validation and undo snapshots. Shared by the GUI and the CLI.
- **`Validator`:** every spec and firmware-rs cangen rule (see below), producing `Issue` records. Issues it can fix carry a `Fix` (`FixKind`, an argument, the menu label), added with `addFix`/`msgFix`.
- **`Topics`:** topic levels across the spec: `complete` (autocomplete for the level being typed), `usages`, `rename` and `collisions`. A level such as `VCU/Status` covers itself and everything under it, never `VCU/StatusX`.
- **`Diff`:** a line diff (common start and end trimmed, then LCS, up to `MAX_CELLS`) and `hunks` with context, for the review before saving.
- **`Fixes`:** what each `FixKind` changes in the JSON (`apply`, which rechecks first, so a stale fix does nothing), plus padding: `padToByte` and `keepAligned` (behind the editor's "Keep byte aligned", pref `autoPad`). The editor's menus are `App.fixMenu`/`applyFixes` (one undo step per file, then an immediate check), opened by right-clicking the Problems list, a point or field row, or the message's problem list. To add a fix: a `FixKind` with its plural description, the `Fix` on the rule in `Validator`, and its case in `Fixes.apply`.
- **`Cli`:** `--validate` and `--candump`.
- **`Theme`:** the palette record; `Ui.T` holds the current one. Themes are One Dark (default), Dracula, Nord, One Light and Native. FlatLaf is applied by reflection only when it's on the classpath. The custom UIs in `Look` paint with `T`.
- **`Families`:** `Family` and `findFamilies` detect messages with the same layout whose topics differ at one level (for example the 11 VCU eFuses). Nothing about families is stored in the JSON.
- **`Can`:** `CanFrame`, `parseCandumpLine` (candump `-L`, default candump output and cansend formats), `specIndex`, `checkFrame` and `decodeFrame`.
- **`TrafficWindow`:** the decode window (Ctrl+Shift+T): logs, pasted lines, and live candump over SSH.
  - The car's constants are near the top: `CAR_WIFI = "Hermesbay"`, `CAR_HOST = "192.168.100.12"`, `CAR_USER = "root"`, `WRONG_WIFI = NuWave, eduroam`; the interface defaults to `can0`.
  - Two views in `mainTabs`: "Raw frames" (`table`, per-frame or per-ID `Agg` rows) and "Decoded" (`decTable`, one row per ID, values painted by `ValuesCell`). "Copy frame" (detail bar, or Cmd/Ctrl+C in either table) copies the selected frame's decoded values (`decodedText`) then its candump line. The Details text only rewrites when it changes, with a `NEVER_UPDATE` caret, so live refreshes keep its scroll position.
  - Detail tabs: "Details" (text), "Points" (`pointsTable` plus `PointGraph`, which shows the last `window` seconds up to `end` = `TrafficWindow.now()`, set by the "Show the last" box, pref `graphWindow`; hidden until "Show graph", `graphShown`) The graph controls sit in the tab row (`TabsWithTrailing`).
  - While frames stream in, `rebuildAggView` and `showPoints` update rows in place (`fireTableRowsUpdated`) when the rows are unchanged, so selections and clicks in progress survive each refresh. Decoded rows are `Group` headers (one per spec file, kept in `groups`, folded via `collapsed`) followed by their `Agg` rows; use `decAgg(row)`.
  - Main tabs: Raw frames, Decoded, then whole-height dashboards (`dashboardActive()` hides the detail pane): "eFuses", "Shutdown loop" (`loopPage`: the `ShutdownPanel`, or a note until 0x123 frames arrive), "VCU faults" and "BMS faults".
  - `dashboards/EfuseBoard.java`: the eFuses page, cards laid out by `EFUSE_LAYOUT` (board label to message ID; SIDE BRBS is assumed to be the spec's "Shutdown eFuse" 0xEF2). An AUTO chip marks `control_state` 1 (`CONTROL_AUTO`; 0 on, 1 auto, 2 off). It holds its window as `w`.
  - `dashboards/FaultsPanel.java`: one fault page per node, listed in `TrafficWindow.faultPanels` (VCU `0x502`, BMS `0x89`): one bit per fault, Critical/Non-Critical and the tile label read from each field's topic; `offer()` is fed every decoded frame of its ID and records each change. `FaultTiles` paints the tiles. It holds its window as `w`.
  - Live sessions: `LiveSpec` is the command and environment, so `connect()` can restart it. After frames have arrived, a dropped connection retries (`RECONNECT_DELAYS_S`) unless `retryable()` says it's an auth, host-key, missing-candump or missing-interface error. `liveSession` spans the reconnects; `proc` is the current process. Optional recording (`openRecorder`/`record`, written by the reader thread, `#` comment lines for gaps). Pause stops `drain()` and the stale clock; frames queue in `pending` (counted by `queued`, capped at `PAUSE_BUFFER`). `note()` keeps a status message from being overwritten by the per-refresh counts.
  - `Keychain`: the SSH password in the macOS Keychain (`security`, written via `security -i` with the password as hex so it's never on a command line; read with `-g`, since `-w` prints non-ASCII passwords as bare hex) or the Linux keyring (`secret-tool`).
  - `Agg` keeps each ID's last 10 arrival times (rate = average gap over the last 3–10 frames), decoded values with change marks, min/max, and a history for the graph. Staleness: no frame for 3 periods and at least 1 s (`STALE_PERIODS`, `STALE_MIN_S`).
  - Per-point decoding is shared with `decodeFrame` through `pointRaw`/`pointValue`; `pointMeta` gives each point's name and its field's unit.
- **`ShutdownLoop`:** the loop model. `STAGES` is the loop order (edit it there), `pins`, `read` and `specNotes`. The views are `ShutdownPanel` and `LoopDiagram`.
- **`Settings`:** the preferences node `tiresias`, migrated once from the old node `ner-cangen-spec-browser`, plus the animations flag.
- **Animation helpers** (`Ui`, `Widgets.FadeUI`): `animate`, `fadeTo`, `FadeUI` and `crossfade`, all switched off by View > Animations.
- **`LoadingOverlay`:** the spinner glass pane shown while files load.
- **`Icons`:** the app icon drawn in code (macOS continuous-corner tile with rows of colored bit blocks), window and Dock install, and `.icns`/`.ico` export.
- **`App`:** the main window: file/message tree, search, menus, undo/redo (per-file JSON snapshots, 200 entries), families, and the tools (find free IDs, fix outdated keys, ID map).
  - Validation after edits waits until typing pauses: `changed()` restarts `validateTimer` (`VALIDATE_DELAY_MS`, 300 ms), and `validateEdits()` updates the problem views only when `sameIssues` says the list changed, so the Problems selection stays. `validateNow()` runs a pending check at once; call it before reading `issues` right after an edit (save does). `revalidateAll()` always checks and redraws at once (loading, F7, theme changes, undo).
- **Navigation and topics in `App`:** `showGoTo` (`GoToDialog`, Cmd+P; `search` scores exact, prefix, word start, substring, then letters in order), history (`visit` is called by the editor's `load` and `showFile`; `history(-1|1)` for Back/Forward, falling back to a message's index when undo replaced its object), `topicSuggestions` (for `Suggest`), `showTopics`/`renameTopicLevel` (`TopicsDialog`, one undo step per file) and `openField`.
- **Saving:** `saveCurrent` and `saveAll` show `ReviewDialog` first while pref `reviewBeforeSave` is on. It diffs the file on disk against `Json.write` (which reproduces the team's files byte for byte), and flags files that changed on disk since they were opened (disk text differs from `savedText`).
- **Rust type names:** the Points tab's Type column shows the type firmware-rs generates (`Spec.rustGenType`, `MessageEditor.shownType`/`typeTip`), with the `c_type` beside it when they differ. Picking a type writes its C name to `c_type` (`rustType`, `cTypeFor`, `RUST_TYPE_CHOICES`), so the validator's messages still say `c_type uint8_t`.
- **Data colors:** `MessageEditor.colorPointCell` and `colorFieldCell` color table cells by kind, using `Ui.topicHtml` (topic levels) and `Ui.typeColor`, always theme colors. `JsonPane` (ui) is the JSON tab: syntax colors on a short timer after typing; its undo (`JsonPane.history`) records plain text edits through a `DocumentFilter`, because Swing's own document edits break once recoloring has split the styled runs.
- **`Suggest` (ui):** the autocomplete drop-down for a text field, in a non-focusable `JWindow` so it works inside table cell editors; used for the Fields tab's topic column and the topics dialog.
- **`IssuesPanel`:** the problems table at the bottom of the main window.
- **`MessageEditor`:** the editor's cards, chosen with a CardLayout.
  - `empty`: the welcome and overview placeholder.
  - `file`: the `MessageGrid` of a file's messages, shown when a file is clicked in the tree.
  - `editor`: the tabs Points, Fields (MQTT topics), Bit layout, Decode test, cansend and JSON.
- **`MessageGrid`:** the file overview tiles, with filtering, keyboard navigation, and compact tiles when the full-size ones don't fit.
- **`BitLayoutPanel`:** the bit layout tab, with drag-to-reorder and animations.

## Conventions and constraints

- Java 21 with no required dependencies (FlatLaf is optional, by reflection). Since the split into package `tiresias`, the app runs from the jar or `./gradlew runApp`, not `java Tiresias.java` (Java 21 can only launch a single source file).
- Formatting is Prettier via Spotless (Prettier 3.8.3 and prettier-plugin-java 2.9.7, printWidth 120, tabWidth 4). Run `./gradlew spotlessApply` before committing; CI fails otherwise. Code between `// spotless:off` and `// spotless:on` is never formatted.
- Colors always come from the theme (`T.red()`, `T.muted()` and so on) so theme switching works; never hard-code colors in UI code (the icon and DMG art are the exceptions).
- Long work runs off the Swing thread and hands results back with `SwingUtilities.invokeLater`.
- User-facing text: plain, specific sentences that say what's wrong and how to fix it, with no jargon the team wouldn't use.
- Keep things easy to change in one obvious place: car network constants in `TrafficWindow`, the loop order in `ShutdownLoop.STAGES`, the platform list in `build.gradle`, and versions in `build.gradle` (bump the app's default `version` there for each release).
- After changes, compile with `-Xlint:all -Xlint:-serial` (currently zero warnings), and check that `--validate` and `--candump` output is unchanged unless the change is meant to alter it.

## Domain: spec rules the validator enforces (firmware-rs cangen specifics)

The rules follow definition-rs (serde structs `CANMsg`, `CANPoint`, `NetField`, `MetaMsg`, `DumbNetField`, `Formatter`, `Sim`) and cangen's `build_struct`/`field_tokens`. An ERROR means firmware-rs fails to read or build the spec, or panics; anything that builds but is probably wrong, or matters only to other tools (C gen, Calypso), is a WARN or INFO that says so.

- Reading the files: cangen reads only `*.json` directly in `can-messages/` (WARN for others). Every file must deserialize: unknown keys are errors (`deny_unknown_fields` everywhere except inside `formatter`), and so are keys given twice. The hints cover `default_value` (now `default`) and `format` (now `formatter: {key, arg}`).
- `null` for an optional key (`key`, `is_ext`, `sim_freq`, `clients`, the point keys other than `size`, a field's `desc`) means "not set" (`Validator.withoutNulls`). `size`, `values` and `clients` are integers in Rust, so `8.0` or `1e1` fails (`wholeLiteral`).
- IDs: `u32::from_str_radix(id.trim_start_matches("0x"), 16)`, so `0X` or whitespace panics (ERROR), and a missing `0x` still parses (WARN: Tiresias needs it). Above 0x7FF without `is_ext`, `StandardId::new(id as u16)` panics, or, when `id & 0xFFFF` fits in 11 bits, silently builds the wrong ID.
- `desc` becomes the struct name `AsPascalCase(desc.to_lowercase().replace(' ', "_"))`: it must be non-empty, unique, not start with a digit, and not be `Msg` or another name the generated code uses (`Id`, `Frame`, `CanMessage`, …; `GENERATED_NAMES`).
- Point names become `AsSnakeCase(name)` accessors (`Spec.heckWords` copies heck's word splitting). Keywords, `new`/`from_bits`/`into_bits`, two points with the same accessor, and names whose generated methods collide (`x`, `with_x`, `set_x`, their `_checked` versions, and `try_with_x`/`try_set_x` for scaled points) are errors. Unnamed parsed points become reserved bits (WARN).
- The Rust type (`Spec.rustGenType`): ieee754_f32 or a divide/multiply formatter gives f32, then c_type float gives f32 and c_type bool on 1 bit gives bool; anything else is an integer sized from the bits (`u8`…`u64`, or `i8`/`i16`/`i32`/`i64` when signed). c_type doesn't change integer types.
- Signed integers (that path only) must be 8, 16, 32 or 64 bits, or cangen panics. Parsed points can be up to 64 bits (over 32 is a WARN).
- `formatter` only applies `divide` and `multiply`; the arg is read as f32 then cast `as u32` (it must stay above 0).
- Little endian: the size must be whole bytes (else cangen panics), and 24, 40, 48 or 56 bits decode wrong (`swap_bytes` on the wider type). With a formatter, float or ieee754 it panics. Mid-byte starts are a WARN (C gen). `endianness` on 8 bits or fewer, and values other than `big`/`little` (treated as big), are WARNs.
- Messages hold at most 64 bits; cangen pads a message that isn't whole bytes itself (WARN). Every field needs `name`, `unit`, `doc` and `values` (whole numbers); out-of-range or padding entries build but publish nothing (WARN). `bidir_mode` must be lowercase `broadcast` or `oneshot`; `configuration` panics in code generation.
- Meta messages (no `id` and no `points`) may only have `desc` and `fields`, and their fields no `values`.
- Decoding is big-endian MSB-first, matching Calypso.

## Domain: the shutdown loop (message 0x123)

- `0x123` "Shutdown Pins" is in vcu.json. Its points, in order: `bms_gpio`, `bots_gpio`, `bspd_gpio`, `hv_c`, `hvd_gpio`, `imd_gpio`, `ckpt_gpio`, `inertia_sw_gpio`, `tsms_gpio` (1 bit each), then 7 bits of padding.
- Each pin samples the loop just after its stage. Where the loop breaks, that pin and every pin after it read 0, and pins before it read 1.
- The break is therefore somewhere from the stage after the last pin reading 1 through the first pin reading 0. That range includes unsensed stages, which are highlighted as suspects.
- `powered(...)` stages, currently only the GLV master switch, count as closed whenever the VCU is sending at all.

## Gotchas

- Gradle must run on the same Java release as the bundled runtime (21), because jlink only links its own release's modules.
- The JDK download cache folder keeps its pre-rename name (`~/.gradle/caches/can-spec-browser-jdks/21/`) so existing downloads are reused.
- Spotless runs Prettier through Node. `build.gradle` finds Node itself, because IntelliJ opened from the Dock lacks the terminal's PATH. Override with `nodeDir=...` in `~/.gradle/gradle.properties`.
- On the developer's Mac (Node 26, npm 12), Spotless's Prettier server hung. The workaround: `brew install node@24` and `nodeDir=/opt/homebrew/opt/node@24/bin`.
- macOS bundles are unsigned. In the launcher script, `-Xdock:icon` and `-Xdock:name` set the Dock icon; the app also sets it again when the window opens.
- Release bundles are cross-built on Linux. Only the `.dmg` step needs macOS.

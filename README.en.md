# MarkFlow

<div align="left">
[中文](README.md) · <b>English</b>
</div>

A Markdown editor designed for Android. It supports browsing, editing, previewing, and managing Markdown and a variety of text files, delivering a fluid writing experience.

## Features

### File Management
- Automatically scans text/Markdown files in `Documents/` and `Download/` (no full-disk scan)
- Automatically excludes media files (video / audio / image, e.g. `.ts`), showing only text files
- **Three mutually-exclusive sections (top to bottom)**: "Recent" → "Markdown" → "Others". Each file appears in exactly one section
  - **Recent**: A FIFO-5 queue (length adjustable via constant). Any open action (tap in list / external open / share / create / import) enqueues the file — moves it to the front if already present, or inserts at the head; when the queue exceeds 5, the oldest is evicted back to its type section. Queue order is unaffected by the sort mode
  - **Markdown**: `.md` / `.markdown` files (excluding those already in the Recent queue)
  - **Others**: remaining text files (code / config / plain text, excluding those already in the Recent queue)
- Multi-select: batch share, batch delete; multi-file share uses `ACTION_SEND_MULTIPLE`
- Sorting: by modified time / by name (applies to the Markdown and Others sections)
- **Starred favorites**: tap the yellow star beside the file name to favorite / unfavorite. Starred files are always pinned to the top within the Markdown and Others sections (the star group is not broken by the sort mode; order within the group follows the mode). The Recent section is unaffected by stars (order stays by open recency; the star mark is shown and togglable). Stars are cleared automatically when a file is deleted / hidden
- Single-file actions: rename (suffix changeable; a "Change extension" confirmation appears when the suffix changes), view details, share
- Create new file (custom name + suffix picker, default `.md`, ~21 suffixes)
- Import existing text documents from the device (atomic write + de-duplication by same name)
- Auto background list refresh after saving, without flicker
- External file names are sanitized (anti path-traversal) before being imported into the private directory

### Editor
- Toggle between edit / preview modes via a **single icon switch** — "eye" in edit mode (tap to preview), "pen" in preview mode (tap to edit); scroll position is preserved across mode switches
- Syntax highlighting hints (code blocks, headings, lists, etc., covering 11 Markdown elements); tiered highlighting: ≤64K chars computed synchronously, >64K chars computed on a background thread with 80ms debounce (no input lag); documents >32K chars or with rich structure (segment count over budget) automatically downgrade to heading-only highlighting in edit mode to avoid long-document lag; all highlight rules are suppressed inside fenced code blocks
- Undo / redo (with cursor-position tracking), shown only in edit mode
- Auto-save (triggers 3s after input stops; does not affect undo/redo history)
- Auto-formatting: list continuation (unordered / ordered / checkbox), indentation preserved, ordered-list numbers, empty list-item cleanup; enabled only for `.md` files
- Manual save + save-status indicator (Unsaved / Saving / Saved)
- Unsaved-changes confirmation on exit ("Save & exit" / "Discard changes")
- **Insert image**: copied into an `images/` dir next to the `.md` (max long edge 2048px, JPEG quality 85, named `mf_{timestamp}_{4-digit random}.jpg` — the `mf_` prefix distinguishes auto copies from user-named images, preventing accidental deletion); a `.nomedia` file is placed and a media rescan is triggered so copied images never appear in the gallery; deleting a `.md` cleans up the auto-named image copies no longer referenced by any existing `.md` (user-placed images are never touched)
- Editor toolbar icon order: **Search → Undo → Redo → Insert image** (edit mode only)
- Segmented editing compatible with handwriting input methods (composition range preserved, multi-stroke character input works)
- Keyboard avoidance: the content area stays full-height; keyboard height is **perceived in real time** (including height switches like pinyin ↔ handwriting); when the keyboard appears, cursor-follow raises the cursor above it, avoiding both occlusion and the blank-content bug caused by compressing the content area; built-in bottom padding so the last line never sticks to the screen bottom
- Hides the keyboard when text is selected, to avoid popping the input method over the copy handle
- Search: keyword match highlighting, precise jump to result (centered), position kept after closing (not pulled back to the cursor)

### Preview
- Markdown rendering (Markwon)
- Syntax-highlighted code blocks (Prism4j, 30+ languages, incl. custom bash / dockerfile / diff / typescript / toml grammars)
- Tables, task lists, strikethrough, superscript / subscript (`~sub~` `^sup^`)
- LaTeX math: inline `$...$` and block `$$...$$`; inline formulas get vertical-alignment compensation to align with Chinese baseline
- Image loading (Coil); relative-path images resolve against the `.md`'s physical directory; missing images fall back to a gray placeholder
- Unsafe image URLs auto-filtered (e.g. Mi Notes custom URIs)
- Light / dark theme toggle (tap the title bar)

### Large Files (≥512KB, unified threshold)
- Large text and large Markdown use the **same 512KB threshold**, all handled via "paginated read-only browsing + segmented editing", **without loading the full content into memory** (avoids crashes and lag from huge documents)
- Browse: continuous paginated read-only, on-demand loading with remote chunk eviction; manual encoding switching; reading-position memory (global byte-offset anchor, debounced persistence, auto-saved on exit)
- Edit: long-press a segment to edit that single chunk (the paging core splits over-long single lines by bytes, keeping memory bounded)
- Large Markdown opens in paginated preview browsing by default, identical to large TXT behavior; oversized md no longer triggers full-text render/highlight crashes

### Supported File Types
- **Markdown** (`.md` `.markdown`): full edit + preview + TOC
- **Syntax-highlighted** (`.sh` `.bash` `.zsh` `.py`... all common code & config types): edit mode + code-card preview
- **Plain text** (`.txt` `.log` `.text`): edit mode only

### Cross-App Support
- Open text files from a file manager or other apps
- Share text / files into MarkFlow from other apps (auto-copied to the private directory before opening)
- Handles `content://` / `file://` URIs
- Supports files without a standard MIME type (e.g. `.toml`)
- Refuses to open unregistered / suffix-less files at the source

### Other
- Immersive (fullscreen) editing mode
- Table of Contents (TOC) view (read-only; entries not yet jumpable; hidden for large Markdown)
- Directory / reading-position memory

## Tech Stack

| Category | Technology |
|---|---|
| Language | 100% Kotlin |
| UI | Jetpack Compose + Material3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt (via KSP) |
| Async | Kotlin Coroutines + Flow |
| Markdown | Markwon (latex / tables / tasklist / strikethrough / superscript / subscript plugins) |
| Highlighting | Prism4j (grammars generated via kapt) |
| LaTeX | JLaTeXMath |
| Images | Coil |
| Navigation | Navigation Compose |
| File Access | Scoped Storage / MediaStore / DocumentFile API |

## Project Structure

```
com.markflow.editor
├── data/
│   ├── local/          # Local preference storage (sort, theme, recent-open queue)
│   └── repository/     # File I/O repositories (scan, read/write, MediaStore/DocumentFile, encoding detect, large-file paging)
├── domain/
│   ├── model/          # Domain models (FileType/FormatRegistry/SortMode/ThemeMode/EditorMode/MarkdownFile etc.)
│   └── util/           # Domain utilities (FileSorter, UndoRedoManager)
├── ui/
│   ├── components/     # Reusable components (BottomActionBar, CodeBlockCard, MarkdownPreview, SearchBar, MarkdownSyntaxHighlighter, VerticalAlignedLatexSpan)
│   ├── navigation/     # Nav graph & route definitions
│   ├── screens/
│   │   ├── editor/     # Editor screen + ViewModel (incl. large-file paged read / segmented edit)
│   │   └── filelist/   # File list screen + ViewModel (three-section mutual-exclusion)
│   └── theme/          # Material3 theme (light/dark manual toggle)
├── util/               # Utilities (Markwon config, Markdown parsing, TOC, syntax highlighter, auto-format, superscript/subscript & LaTeX align plugins, encoding detect, large-text paged reading, Prism4j custom grammars)
├── io/noties/prism4j/languages/  # Custom Prism4j grammars (bash, diff, dockerfile, toml, typescript)
├── MainActivity.kt     # Main activity (Intent dispatch / share entry)
└── MarkFlowApp.kt      # Application entry (Hilt)
```

## Building

### Requirements
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 35
- Gradle 8.7+

### Build Commands

```powershell
# Debug build (unobfuscated, debuggable)
.\gradlew.bat assembleDebug

# Release build (obfuscated, compressed, signed)
.\gradlew.bat assembleRelease

# Clean build artifacts
.\gradlew.bat clean
```

> If the build fails with a Kotlin daemon connection error (`AccessDeniedException` / `Could not connect to Kotlin compile daemon`), run `.\gradlew.bat --stop` first and retry.

### Debug vs Release

| | Debug | Release |
|---|---|---|
| Obfuscation (R8) | Off | On |
| Resource shrinking | Off | On |
| Application ID | `com.markflow.editor.debug` | `com.markflow.editor` |
| Version name | `1.2.0-debug` | `1.2.0` |
| Signing | Debug key (auto) | Release key (keystore) |
| Debuggable | Yes | No |
| Logging | Full | R8 removes `Log.v/d/i`, keeps only `Log.w/e` (for troubleshooting) |

### Output

| Variant | Path |
|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

### LaTeX & Obfuscation

JLaTeXMath heavily uses reflection (`Class.forName` / `getMethod`), so the ProGuard rules in release must keep the following classes or block formulas will fail with NPE:

```properties
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }
```

Also `-keep class io.noties.markwon.** { *; }` keeps markwon-latex's drawable classes, so the inline-formula vertical-alignment fix (which calls `isBlock()` via reflection to distinguish inline / block formulas) works in release builds.

### Release Signing

Release builds need a signing key. The keystore lives in the user's home directory `~/.markflow/` (not in the repo, to keep keys out of the repository):

```properties
# Location: ~/.markflow/keystore.properties
storeFile=markflow-release.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

- `storeFile` supports relative (to `~/.markflow/`) or absolute paths
- If `~/.markflow/keystore.properties` is missing, lacks required keys, or the keystore file is absent, the Release build **fails directly** (it never silently falls back to the debug key); this does not affect Debug builds or IDE sync

### Logging Strategy
- Release uses R8 (`-assumenosideeffects`) to strip `Log.v/d/i`, keeping only `Log.w/e/wtf`
- All source logs are in **exception branches** (file I/O failures, MediaStore errors, formula rendering failures, etc.); normal runs produce **no logs**. Error volume is minimal and useful for troubleshooting; it does not consume device storage (logcat is an in-memory ring buffer, not written to disk continuously)

## Requirements

- Android 8.0 (API 26) and above
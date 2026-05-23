# JPod: Terminal Reality POD archive utility

A Java 17 desktop tool for viewing, extracting, and building Terminal Reality **POD** (version 1) archives — the proprietary container format used by games including *Monster Truck Madness 1 & 2*, *CART Precision Racing*, *Hellbender*, *Terminal Velocity*, and *Fury3*.

---

## Features

### Archive viewing
- Open any `.pod` file and browse its contents in a file-viewer style table with Name / Size / Description columns.
- Archive folders and subfolders are shown with icons and start collapsed by default, so root files and top-level folders are easier to scan.
- Click column headers to sort by name, size, or description; clicking again reverses the sort, and a third click returns to the default archive order.
- Use the quick search field above the table to filter visible entries instantly, or open the Advanced search dialog for targeted searches and jump-to selection.
- The archive comment (80-char POD header field) is shown in an editable field below the toolbar.
- JPod keeps a list of the 10 most recently opened files in a per-user JSON config stored in the operating system's preferences/config location.

### Extraction
- **Extract All** — writes every entry to a chosen folder, recreating the archive's subfolder structure automatically.
- **Extract Selected** — extracts highlighted entries to a chosen folder, with an option to preserve or flatten the archive folder structure.

### Archive building & editing
- **New Archive** — start an empty archive from scratch.
- **Open Response List File** — load a `.lst` text file (one filename per line) and resolve each file from disk to build the entry list. Supports an optional `filename,archiveName` syntax per line.
- **Add Files** — append individual files via a file picker or by **drag-and-dropping** files directly onto the entry table.
- **Remove** — delete selected entries from the in-memory list.
- **Replace** (right-click) — swap a single entry's data with a file from disk, keeping the original archive name.
- **Save As** — write the current entry list to a new `.pod` file using the exact Terminal Reality binary layout.

### Reports
- **Save .inf** — exports a fixed-column text report (filename, total size, entry count, comment, and a padded name / size / offset table).
- **Save .lst** — exports a plain entry-name list compatible with the manifest loader above.

### File preview
Double-click any entry (or press Preview from the right-click menu) to open a type-aware preview:

| Extension | Preview |
|---|---|
| `.raw`, `.clr` | 8-bit paletted image decoded with the matched `.act` palette (see [Palette resolution](#palette-resolution)). Art textures (64×64) are shown at 4× zoom. Non-standard RAW sizes can be previewed by selecting width, height, and palette manually. |
| `.act` | 16×16 colour swatch grid; hover shows the index and hex value. |
| `.wav` | PCM audio player (play / pause / stop, position readout). |
| `.bmp`, `.png`, `.jpg`, … | Standard images via `javax.imageio`. |
| `.txt`, `.def`, `.nav`, `.lvl`, `.sit`, `.lst`, `.ini`, `.cfg`, `.tex`, `.tnl`, `.ttx`, `.trk`, `.trn`, `.ndx`, `.mic`, and other text formats | Scrollable monospaced text viewer. |
| Anything else | Hex dump of the first 4 096 bytes. |

#### Palette resolution
For `.raw` and `.clr` files, the palette is resolved in this order:
1. Same base name in the same archive directory (e.g. `ART\DEMO1.RAW` → `ART\DEMO1.ACT`)
2. `VGA.ACT` anywhere in the archive
3. `METALCR2.ACT` anywhere in the archive (MTM1 default palette)
4. Any other `.act` file in the archive
5. `metalcr2.act` bundled as a classpath resource (`src/main/resources/palettes/`)
6. Greyscale fallback

When a RAW file does not match the common built-in sizes, JPod offers a preview dialog where you can:
- choose width and height manually
- swap width and height instantly
- choose a palette from the same-name ACT, ACT files inside the POD, `VGA.ACT`, `METALCR2.ACT`, or greyscale

Common non-square defaults are preselected automatically:
- `64000` bytes -> `320 x 200`
- `256000` bytes -> `640 x 400`
- `307200` bytes -> `640 x 480`

### Mount in pod.ini
Adds the open archive to the game's `pod.ini` mount list. JPod uses `99` as the recommended limit, warns when the list is already larger, and still mounts the POD instead of blocking the action. It searches the archive's folder and its parent and detects duplicates.

---

## What JPod does not do

- **No MOD music playback** — Terminal Reality's `.mod` files (6-channel ProTracker) require a tracker player. Extract the file and play it in an external player (e.g. VLC, OpenMPT).
- **No in-place archive editing** — changes are always written to a *new* file via Save As; the original POD is never modified.

---

## License

JPod is licensed under the Apache License 2.0.

---

## Download

Download the latest build from the [Releases](../../releases) page.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 17 or later |
| Build tool | Maven 3.8+ |

No runtime dependencies beyond the JDK standard library.

---

## Build & run

```bash
# Build a runnable JAR
mvn package

# Run
java -jar target/jpod.jar
```

---

## Project layout

```
src/main/java/com/mtm2/jpod/
├── JPodApp.java               Entry point
├── PodSession.java            Shared mutable state for the current archive
├── io/
│   ├── pod/
│   │   ├── PodArchive.java        Immutable in-memory archive model
│   │   ├── PodArchiveReader.java  Reads a .pod file into PodArchive
│   │   └── PodArchiveWriter.java  Builds and writes a .pod file from blobs
│   ├── PodExtractService.java     Extracts entries to disk
│   ├── PodManifestParser.java     Parses .lst manifest files
│   ├── PodReportExporter.java     Writes .inf and .lst reports
│   ├── PodIniMounter.java         Adds a POD to pod.ini
│   └── RawImageDecoder.java       Decodes .raw / .clr / .act image data
└── ui/
    ├── MainWindow.java            Main application window
    ├── PreviewWindow.java         Type-aware file preview
    ├── AudioPlayerDialog.java     WAV playback
    ├── BuildArchiveDialog.java    New-archive configuration
    ├── ExtractOptionsDialog.java  Extract destination & options
    ├── SearchDialog.java          Entry search
    └── AboutDialog.java
src/main/resources/palettes/
    README.txt   ← drop metalcr2.act here for the default RAW palette
```

---

## References

- [Monster Truck Madness Guild](https://www.mtm2.com/%7Emtmg/index.shtml)

# JPod
<<<<<<< HEAD
Terminal Reality POD archive utility
=======

A Java 17 desktop tool for viewing, extracting, and building Terminal Reality **POD** (version 1) archives — the proprietary container format used by games including *Monster Truck Madness 1 & 2*, *CART Precision Racing*, *Hellbender*, *Terminal Velocity*, and *Fury3*.

---

## Features

### Archive viewing
- Open any `.pod` file and browse its directory in a sortable Name / Size / Offset table.
- The archive comment (80-char POD header field) is shown in an editable field below the toolbar.
- Search entries by name substring or file size.

### Extraction
- **Extract All** — writes every entry to a chosen folder, recreating the archive's subfolder structure automatically.
- **Extract Selected** — extracts highlighted entries; optionally merges them into a single output file.

### Archive building & editing
- **New Archive** — start an empty archive from scratch.
- **Open Manifest** — load a `.lst` text file (one filename per line) and resolve each file from disk to build the entry list. Supports an optional `filename,archiveName` syntax per line.
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
| `.raw`, `.clr` | 8-bit paletted image decoded with the matched `.act` palette (see [Palette resolution](#palette-resolution)). Art textures (64×64) are shown at 4× zoom. |
| `.act` | 16×16 colour swatch grid; hover shows the index and hex value. |
| `.wav` | PCM audio player (play / pause / stop, position readout). |
| `.bmp`, `.png`, `.jpg`, … | Standard images via `javax.imageio`. |
| `.txt`, `.def`, `.nav`, `.lvl`, `.sit`, `.lst`, `.ini`, `.cfg`, and other text formats | Scrollable monospaced text viewer. |
| Anything else | Hex dump of the first 4 096 bytes. |

#### Palette resolution
For `.raw` and `.clr` files, the palette is resolved in this order:
1. Same base name in the same archive directory (e.g. `ART\DEMO1.RAW` → `ART\DEMO1.ACT`)
2. Any other `.act` file in the same archive subdirectory
3. `METALCR2.ACT` anywhere in the archive (MTM1 default palette)
4. `metalcr2.act` bundled as a classpath resource (`src/main/resources/palettes/`)
5. Greyscale fallback

### Mount in pod.ini
Adds the open archive to the game's `pod.ini` mount list (32-POD maximum). Searches the archive's folder and its parent; detects duplicates and a full list.

---

## What JPod does not do

- **No MOD music playback** — Terminal Reality's `.mod` files (6-channel ProTracker) require a tracker player. Extract the file and play it in an external player (e.g. VLC, OpenMPT).
- **No in-place archive editing** — changes are always written to a *new* file via Save As; the original POD is never modified.

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
src/main/java/com/mtm2/winpod/
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
>>>>>>> e7ca58d (initial release)

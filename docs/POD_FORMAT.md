# Terminal Reality POD archive family: format specification

Version 1.0.0, 2026-09-01.

Covers the container formats used by *Monster Truck Madness 1 and 2*, *Terminal
Velocity*, *Fury3*, *Hellbender*, *CART Precision Racing*, *Nocturne*, *4x4 Evo*
and *Fly!*: classic **POD1**, the **POD1-64** long-name extension, **POD2**, and
**EPD**.

A machine-readable companion, [`pod-format.json`](pod-format.json), encodes the
same structures as data.

---

## 1. How to read this document

Every claim carries a confidence marker, because the parts of this format that
are documented upstream, the parts derived from shipped archives, and the parts
still unknown are genuinely different in kind.

| Marker | Meaning |
|---|---|
| **[V]** | Verified against shipped archives. The evidence is in [Appendix A](#appendix-a-evidence). |
| **[D]** | Stated by an upstream document. See [References](#references). |
| **[I]** | Implemented by readers in this family and self-consistent, but no shipped archive was available to confirm it. |
| **[U]** | Unresolved. Documented here so it is not silently guessed at. |

A reader that implements only the **[V]** and **[D]** parts will open every
archive the authors have been able to obtain.

---

## 2. Byte conventions

- All integers are **little-endian**. **[V]**
- Text is 8-bit, **ISO-8859-1** (code page 28591). No archive examined uses a byte
  above 0x7E in a name. **[V]**
- Strings live in fixed-width fields and are **NUL-terminated inside the field**.
  A reader stops at the first `0x00` or at the field edge, whichever comes first. **[V]**
- Bytes after the terminator are **not** guaranteed to be zero. What is found
  there varies by producer and is specified per format below. **[V]**
- Entry offsets are absolute offsets from the start of the file. **[V]**
- Length and offset fields are unsigned 32-bit. The item count is signed 32-bit
  and is never negative in practice. **[V]**

### 2.1 Reading a fixed-width string field

```
read_string(buffer, offset, width):
    end = offset
    limit = min(offset + width, len(buffer))
    while end < limit and buffer[end] != 0x00:
        end = end + 1
    return decode_latin1(buffer[offset : end]) with leading and trailing
           code points <= U+0020 removed
```

The trim removes every code point at or below `U+0020`, control characters
included. This matters: it is what Java's `String.trim()` does and what
`String.Trim()` in .NET does **not** do, and the difference changes whether an
entry with a stray leading control byte decodes to a usable name. **[V]**

---

## 3. Format dispatch

POD1 has no signature, so it is the fallback. Check signatures first. **[V]**

| Bytes 0..3 | Format |
|---|---|
| `64 74 78 65` (`dtxe`) | EPD |
| `50 4F 44 32` (`POD2`) | POD2 |
| anything else | POD1 family: classic or POD1-64, resolved by validation ([§5.1](#51-detecting-which-pod1-directory-a-file-uses)) |

A file shorter than 84 bytes cannot be any member of the family.

---

## 4. POD1 (classic)

The format of MTM1, MTM2, Terminal Velocity, Fury3, Hellbender and CPR. **[V]**

### 4.1 Header

| Offset | Size | Type | Field |
|---:|---:|---|---|
| `0x00` | 4 | `int32` | Number of directory entries |
| `0x04` | 80 | `char[80]` | Archive comment |
| `0x54` | | | Start of the directory table |

The comment field is a NUL-terminated string. Like a directory name field it may
carry bytes after its terminator: in the community archive `POWER.POD` the field
opens with a terminator and is followed by 79 bytes of heap junk. A reader takes
the string and ignores the rest; a writer rebuilding that archive must put the
original bytes back ([§8](#8-writer-rules) rule 5). **[V]**

### 4.2 Directory entry

Entry `i` begins at `84 + i * 40`. Each record is 40 bytes:

| Relative offset | Size | Type | Field |
|---:|---:|---|---|
| `0x00` | 32 | `char[32]` | Name field, see [§4.3](#43-the-name-field) |
| `0x20` | 4 | `uint32` | Payload length in bytes |
| `0x24` | 4 | `uint32` | Absolute payload offset |

The stored path uses a backslash separator and is conventionally upper case. The
usable name budget is **31 bytes**, the whole path included: any directory
prefix such as `ART\`, the stem, the extension, and the terminator. **[D][V]**

### 4.3 The name field

The name field is **not** simply a path followed by padding. It holds one or two
NUL-terminated strings:

```
+--------------------------------+
| path \0 | palette \0 | 00 ...   |   32 bytes total
+--------------------------------+
     ^          ^
     |          +-- optional: see 4.4
     +-- the entry path, always present
```

A reader locating entries must use only the first string. The second is
supplementary and may be absent. **[V]**

### 4.4 The palette record

**[V]** In MTM1, Terminal Velocity, Fury3 and Hellbender, the packer wrote a
second NUL-terminated string after the path on every `.RAW` entry: the file name
of the `.ACT` palette that art was authored against.

```
"ART\BASHP.RAW" 00 "BIONSHIP.ACT" 00 00 00 00 00 00
 \_____________/    \____________/
   entry path         palette record
```

Rules, without exception across 9 967 occurrences in 9 archives from 4 games:

1. Only entries whose path ends in `.RAW` carry a palette record.
2. There is never a third string in the field.
3. The value is a bare file name with no directory part, always ending `.ACT`.
4. The record begins at exactly `len(path) + 1`, immediately after the
   terminator.
5. The remainder of the field after the palette record's own terminator is zero.
6. A record is written only when it fits: `len(path) + 1 + len(palette) + 1 <= 32`.

MTM2 and CPR do not write palette records; their name fields are zero-filled
after the terminator. The split is generational, not per-title. **[V]**

**Resolution.** The named `.ACT` is a bare file name and must be matched against
the *file name* of each entry, not its full path: `BIONSHIP.ACT` refers to
`ART\BIONSHIP.ACT`. In 8 of the 9 archives the palette is present in the same
archive. MTM1 `TRUCK.POD` names `METALCR2.ACT`, which ships in `GAME.POD`, so a
reader must tolerate a palette record that does not resolve locally and fall back. **[V]**

**Guard against junk.** Not every second string is a palette record. The community
archive `POWER.POD` carries 30 of them, 21 on non-`.RAW` entries, none ending in
`.ACT`: uninitialised buffer contents from whatever tool built it. A reader must
therefore accept a second string as a palette record only when the entry path ends
in `.RAW` **and** the string ends in `.ACT`, and must treat anything else as
opaque. **[V]**

**Why it matters.** The palette is not derivable from the path. Against the
stored value, the common heuristic of "any `.ACT` in the same directory"
disagrees on 8 221 of 8 224 RAW entries in MTM1 `GAME.POD`, Fury3 `FURY3.POD`,
Terminal Velocity `CDROM.POD` and Hellbender `GAME.POD`, because it selects
whichever palette happens to appear first in the archive. Where a palette record
exists it is authoritative and should take priority over every name-based rule. **[V]**

**Status.** No upstream document mentions this field; `ENGINE_LIMITS.md`
describes a single 31-character path. Whether any engine reads it is **[U]**.
It is safe to consume as a hint and unsafe to depend on.

### 4.5 Payload region

Payloads follow the directory in entry order, concatenated with no alignment,
padding or separator. The first payload begins at `84 + count * 40`. In every
archive examined the last payload ends exactly at end of file. **[V]**

---

## 5. POD1-64 (extended long-name directory)

**[D]** The engine notes describe a second directory form used when a path
exceeds the classic budget: "Any stem > 20 -> C-Pod writes the EXTENDED directory
(64-byte names, version-tagged header; ~50-char stems). Only the new engine
mounts these."

**[I]** The layout below is what the readers in this family implement. No shipped
extended archive was available to confirm it.

### 5.1 Header

Believed unchanged from classic POD1: a 4-byte count and an 80-byte comment, with
the directory beginning at byte 84. **[I]**

### 5.2 Directory entry

Entry `i` begins at `84 + i * 72`. Each record is 72 bytes:

| Relative offset | Size | Type | Field | Difference from classic |
|---:|---:|---|---|---|
| `0x00` | 64 | `char[64]` | Name field | Widened from 32 |
| `0x40` | 4 | `uint32` | Payload length | Moved |
| `0x44` | 4 | `uint32` | Absolute payload offset | Moved |

The name budget becomes **63 bytes**. Nothing else changes: the payload region is
still a plain concatenation addressed by offset and length. **[I]**

Whether a palette record ([§4.4](#44-the-palette-record)) may appear in a 64-byte
field is **[U]**. No producer of extended archives is known to write one. A
reader should apply the same parse, since the rule costs nothing.

### 5.3 Detecting which POD1 directory a file uses

POD1 carries no version marker that any available archive exhibits, so the layout
is resolved by validating it: **[I]**

```
read_pod1(bytes):
    count = int32(bytes, 0)
    reject unless 1 <= count <= 8192
    comment = read_string(bytes, 4, 80)

    for (name_width, record_size) in [(32, 40), (64, 72)]:
        entries = try_directory(bytes, count, name_width, record_size)
        if entries is not null:
            return archive(entries, layout = name_width == 32 ? POD1 : POD1_64)
    reject

try_directory(bytes, count, name_width, record_size):
    if 84 + count * record_size > len(bytes): return null
    for i in 0 .. count-1:
        base   = 84 + i * record_size
        path   = read_string(bytes, base, name_width)
        length = uint32(bytes, base + name_width)
        offset = uint32(bytes, base + name_width + 4)
        if not plausible_path(path):                      return null
        if offset > len(bytes):                           return null
        if length > len(bytes) - offset:                  return null
    return entries

plausible_path(s):
    s is non-empty
    and len(s) <= 63
    and s contains no code point < 0x20
    and s contains no ':'
```

Classic is tried first so an ordinary archive is never reported as extended.
The bounds test is written as `offset > size or length > size - offset`, never
`offset + length > size`, which can overflow 32-bit arithmetic. **[V]**

### 5.4 The version tag

**[U]** The engine notes say production extended archives carry a
"version-tagged header". No available document gives the tag bytes, its offset, or
the resulting directory origin. `CPOD_LONG_NAMES.md`, cited as the companion
contract, was published on 2026-08-11 and is a naming-policy document: it fixes an
18-character stem that must survive a pack and unpack round trip, notes the
derived-name suffixes `_N`, `_AO`, `_MASK` and `_DTL` that consume 2 to 5
characters, restates the 31-character POD1 field, and warns against internal
`char[13]`, `char[16]` and `char[9]` buffers. It specifies no binary layout.
`FILENAME_CONVENTIONS.md`, also cited, returns 404.

Consequently:

- The layout in [§5.2](#52-directory-entry) is the untagged interpretation.
- A tagged archive whose directory does not begin at byte 84 will not be read by
  this specification.
- Retain any known-good extended archive as a fixture. If one appears, detection
  must be revised from the observed bytes, not guessed.

---

## 6. POD2

Used by *Nocturne* and *4x4 Evo 1 and 2*. **[I]** No POD2 archive was available;
the layout below is what these readers implement, from published references.

### 6.1 Header

| Offset | Size | Type | Field |
|---:|---:|---|---|
| `0x00` | 4 | `char[4]` | `POD2` |
| `0x04` | 4 | `uint32` | CRC-32 of bytes `0x08` through EOF |
| `0x08` | 80 | `char[80]` | Archive comment |
| `0x58` | 4 | `uint32` | Number of directory entries |
| `0x5C` | 4 | `uint32` | Audit-trail count |
| `0x60` | | | Start of the directory table |

### 6.2 Directory entry

Entry `i` begins at `96 + i * 20`. Each record is 20 bytes:

| Relative offset | Size | Type | Field |
|---:|---:|---|---|
| `0x00` | 4 | `uint32` | Offset of the name within the name table |
| `0x04` | 4 | `uint32` | Payload length |
| `0x08` | 4 | `uint32` | Absolute payload offset |
| `0x0C` | 4 | `uint32` | Unix timestamp |
| `0x10` | 4 | `uint32` | Payload CRC-32 |

### 6.3 Name table

Immediately after the directory, at `96 + count * 20`, sits a blob of
NUL-terminated names. Each entry's name begins at `name_table_origin + path_offset`
and runs to its terminator. Names are variable length, so the 31-byte budget of
POD1 does not apply.

### 6.4 Checksums and audit trail

POD2 uses the non-reflected CRC-32 polynomial `0x04C11DB7`, initial value
`0xFFFFFFFF`, and no final XOR (the CRC-32/MPEG-2 convention). The archive CRC
covers every byte after the checksum field. Each entry CRC covers only its payload.

Audit records follow the payloads and are 312 bytes each: user `char[32]`, Unix
timestamp `uint32`, action `uint32` (`0` add, `1` remove, `2` change), entry path
`char[256]`, then old timestamp, old size, new timestamp, and new size as four
`uint32` values. JPod and KPod preserve loaded records and optionally append records
for add, remove, replace, rename, and move operations. **[I]**

---

## 7. EPD

Used by *Fly!*. **[V]** against one archive, `SC24.EPD`, 54 entries.

### 7.1 Header

| Offset | Size | Type | Field |
|---:|---:|---|---|
| `0x00` | 4 | `char[4]` | `dtxe` |
| `0x04` | 4 | `char[4]` | Four-character archive title |
| `0x08` | 136 | | Unidentified, non-zero |
| `0x90` | 4 | `uint32` | Number of directory entries |
| `0x94` | 124 | | Unidentified, non-zero |
| `0x110` | | | Start of the directory table |

### 7.2 Directory entry

Entry `i` begins at `0x110 + i * 80`. Each record is 80 bytes:

| Relative offset | Size | Type | Field |
|---:|---:|---|---|
| `0x00` | 4 | `char[4]` | Path prefix |
| `0x04` | 60 | `char[60]` | Path remainder |
| `0x40` | 4 | `uint32` | Payload length |
| `0x44` | 4 | `uint32` | Absolute payload offset |
| `0x48` | 4 | `uint32` | Timestamp, Unix epoch seconds |
| `0x4C` | 4 | `uint32` | Unidentified, distinct per entry, probably a checksum |

### 7.3 Reconstructing the path

The name is split across the 4-byte prefix and the 60-byte remainder: **[V]**

```
if remainder starts with '\' and prefix is non-empty and matches [A-Z0-9_]+:
    path = prefix + remainder            e.g. "MAPS" + "\SC24N.ACT"
else if remainder is non-empty:
    path = remainder
else:
    path = read_string(record, 0, 64)    the whole field as one string
```

A lower-case prefix is rejected by the `[A-Z0-9_]` test and the remainder is used
alone, which is deliberate: it distinguishes a directory prefix from payload that
happens to sit in those four bytes.

### 7.4 Padding is uninitialised memory

**[V]** Unlike POD1, the space after an EPD name's terminator holds heap junk. In
`SC24.EPD` all 54 records are padded with the repeating pattern `BA AD F0 0D`, the
Windows debug-heap marker for uninitialised memory:

```
"MAPS" "\SC24N.ACT" 00 BA 0D F0 AD BA 0D F0 AD BA ...
```

Nothing may be inferred from EPD padding. Readers must stop at the terminator, and
writers should zero-fill rather than reproduce it.

---

## 8. Writer rules

**[V]** unless noted.

1. **Choose the narrowest directory for a new POD1 archive.** Emit classic POD1
   when every complete directory field fits in 32 bytes. Promote to POD1-64 when
   a path or embedded palette record requires it. Preserve POD1-64 when rewriting
   an archive loaded with the wider layout unless conversion was explicitly chosen.
2. **Count the whole path.** Any prefix such as `ART\` or `MODELS\`, the stem, the
   extension, and the terminator all count against the budget.
3. **Reject, do not truncate.** A path that does not fit the widest available
   field is an error. Silently shortening a name produces an archive that mounts
   and then fails to find its own content.
4. **NUL-terminate every name.**
5. **Preserve fixed-width fields when rebuilding an existing archive.** An entry
   read from an archive should be written back with its original name field byte
   for byte whenever it fits the layout being emitted, and the header comment
   field likewise while the comment text is unedited. This is what keeps palette
   records ([§4.4](#44-the-palette-record)) alive through an open-and-save cycle.
   Rebuilding a field from its string alone silently discards whatever followed
   the terminator.
6. **Zero-fill an unknown remainder.** For an entry with no original field, write
   the path, a terminator, and zeros.
7. **Recompute offsets.** Payload offsets follow the directory in entry order;
   do not carry the source archive's offsets across a rebuild.
8. **Do not invent palette records.** MTM2-era tools never wrote them, and no
   engine is known to read them.
9. **Renames preserve RAW metadata.** Rebuild the directory field with the new
   path and the original second palette string; promote if needed, and reject the
   edit if the complete field cannot fit 64 bytes.
10. **POD2 is explicit.** POD1 is the authoring default. POD2 serialization and
    conversion are selected deliberately and must not affect POD1 detection or layout.

### 8.1 Round-trip property

A conforming writer, given the entries of an archive in their original order with
their original comment and name fields, reproduces that archive byte for byte.
This holds for **all 66 POD1 archives surveyed**, palette-bearing and junk-bearing
alike. It relies on payloads being stored contiguously in directory order, which
every surveyed archive satisfies. **[V]**

---

## 9. Validation

A reader should reject an archive when any of the following holds. All were
observed to be necessary while distinguishing the two POD1 directory widths. **[V]**

| Check | Rationale |
|---|---|
| File shorter than 84 bytes | Cannot hold a header |
| Item count < 1 or > 8192 | Guards against reading a non-POD file. Hellbender's `GAME.POD` has 4 341 entries, so a lower cap is wrong |
| Directory table extends past end of file | Truncated or misidentified |
| Any entry path is empty | A valid directory has no unnamed entries |
| Any entry path contains a code point < `0x20` or a `:` | Characteristic of a misparsed directory |
| Any entry path is longer than 63 bytes | Cannot be terminated inside the widest field |
| `offset > file_size` or `length > file_size - offset` | Payload outside the file. Written this way to avoid 32-bit overflow |

A writer's entry cap should match its reader's. A tool that opens an archive it
cannot save back is a defect.

---

## Appendix A: evidence

Survey of every archive available to the authors, 2026-09-01: 67 unique files
(66 POD1, 1 EPD, no POD2), 41 212 entries. Duplicate files were counted once.

### A.1 Palette records by title

| Title | Files | Entries | Archives with records | Palette records |
|---|---:|---:|---:|---:|
| MTM1 | 5 | 3 543 | 3 | 2 450 |
| Terminal Velocity | 2 | 2 689 | 1 | 877 |
| Fury3 | 4 | 4 934 | 3 | 2 752 |
| Hellbender | 2 | 5 547 | 2 | 3 888 |
| MTM2 | 19 | 9 534 | 0 | 0 |
| CPR | 22 | 7 677 | 0 | 0 |
| Community | 13 | 7 288 | 0 | 0 (see A.4) |

Palette values seen: `METALCR2.ACT`, `DEMO.ACT` (MTM1); `ARTIC.ACT`,
`ASTROID.ACT`, `BUGHUNT.ACT`, `GEIGER.ACT`, `CORE4.ACT` among 34 (TV);
`EGYPT.ACT`, `TERRAN.ACT`, `WATER.ACT`, `RED2.ACT`, `BELAZURE.ACT`, `FUTRO.ACT`,
`BIONSHIP.ACT` among 47 (Fury3); `KREASH.ACT`, `MORBOS.ACT`, `FLOAT.ACT`,
`SHIP.ACT`, `ROID.ACT` among 12 (Hellbender); `VGA.ACT` (Fury3 and Hellbender
startup archives).

### A.2 Properties confirmed across all 9 967 palette records

- Carried only by `.RAW` entries: 9 967 of 9 967.
- Carried by non-`.RAW` entries: 0.
- Fields containing a third string: 0.
- Values not ending in `.ACT`: 0.
- Values absent from the same archive: only MTM1 `TRUCK.POD`, 135 entries naming
  `METALCR2.ACT`, which ships in `GAME.POD`.

Counting every second string in the survey rather than only conforming ones gives
9 971, of which 9 832 resolve to an `.ACT` present in the same archive. The
difference is those 135 plus the 4 junk strings of A.4.

### A.3 Round trip

All 66 POD1 archives reproduce byte for byte when read and written back with the
comment field and name fields preserved. Rebuilding those fields from their
strings alone reproduces 64 of 66: `FURYSE.POD` differs by 9 384 bytes, exactly its
palette records, and `POWER.POD` differs in its comment field.

### A.4 Counter-example: `POWER.POD`

A community archive of 30 entries. Its comment field holds a terminator followed
by heap junk, and 30 of its name fields carry a second string, 21 of them on
non-`.RAW` entries, with values such as `h`, `N` and `\xc8\xa6\xf6\xbf`. None ends
in `.ACT`. This is what the guard in [§4.4](#44-the-palette-record) exists for, and
it is why a reader must not treat post-terminator bytes as meaningful by default.

### A.5 Not covered

- No POD1-64 archive was available. Everything in [§5](#5-pod1-64-extended-long-name-directory) marked **[I]** is unverified.
- No POD2 archive was available. All of [§6](#6-pod2) is unverified.
- One EPD archive was available, so [§7](#7-epd) rests on a single sample.

---

## Appendix B: producer quirks

| Producer | Name field after the terminator |
|---|---|
| MTM1, TV, Fury3, Hellbender packer | Palette record on `.RAW` entries, then zeros |
| MTM2, CPR packer | Zeros |
| EPD packer (*Fly!*) | `BA AD F0 0D` repeated, uninitialised heap |
| Community tools surveyed | Usually zeros; `POWER.POD` leaves heap junk in both the comment field and name fields |

A reader must treat everything past the first terminator as advisory, and must
never let its contents affect entry lookup.

---

## References

- [MTM2 Engine Content Limits](https://www.mtm2.com/~mtmg/misc/ENGINE_LIMITS.md) - POD1 name budgets, the extended-directory design note
- [C-Pod long-name contract](https://www.mtm2.com/~mtmg/misc/CPOD_LONG_NAMES.md) - naming policy, 2026-08-11. No binary layout
- [Monster Truck Madness Guild](https://www.mtm2.com/%7Emtmg/index.shtml)
- [EPD Format Reference](https://github.com/jopadan/termpod/wiki/EPD-Format-Reference)
- [Pod 2 Format Reference](https://github.com/jopadan/termpod/wiki/Pod-2-Format-Reference)

Implementations that follow this specification: [JPod](../../JPod) (Java),
[KPod](../../KPod) (C#), [JSPod](../../JSPod) and JSTruckViewer (JavaScript).

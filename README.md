# NITF over CDS

Send NITF (National Imagery Transmission Format) files across a streaming
cross-domain solution (CDS) that only accepts a single XML document per
message and validates against an XSD.


We need a way to:

1. Convert NITF to a form the CDS can inspect.
2. Preserve round-trip fidelity so the high side reconstructs the original
   NITF byte-for-byte.
3. Bind the approved metadata to the actual pixel bytes so an attacker
   cannot swap the payload after inspection.

## Approach

Use [Apache Daffodil](https://daffodil.apache.org/) plus a forked copy of
the Owl Cyber Defense NITF DFDL schema to convert NITF to XML and back.
Wrap Daffodil's XML in an envelope (`NitfMessage`) that inlines every
binary payload as base64 with a SHA-256 manifest, so the entire message
is one self-contained XML document.

```
LOW SIDE                            CDS                         HIGH SIDE
────────                            ───                         ─────────
file.nitf                                                       reconstructed.nitf
  │                                                                    ▲
  │ daffodil parse                                                     │ daffodil unparse
  ▼                                                                    │
parsed.xml + daffodil-blobs/                     rewritten.xml + restored blobs
  │                                                                    ▲
  │ nitf_send.py                                                       │ nitf_recv.py
  ▼                                                                    │
envelope.xml ──────── TCP ───── guard (XSD + policy) ───── TCP ─────► envelope.xml
```

### Wire format

One `NitfMessage` per NITF. Validated against `nitf_envelope.xsd`.

```xml
<NitfMessage xmlns="urn:cds:nitf:1" version="1">
  <Manifest>
    <Blob index="0" size="524288000" sha256="9f8e7d...a1b2"/>
    ...
  </Manifest>
  <Blobs>
    <BlobData index="0">BASE64...</BlobData>
    ...
  </Blobs>
  <Payload>
    <nitf:NITF xmlns:nitf="urn:nitf:2.1">...Daffodil output with URIs rewritten to cds:blob:N...</nitf:NITF>
  </Payload>
</NitfMessage>
```

Element order matters: `Manifest` first so a streaming guard knows the
expected hashes before the bytes arrive; `Blobs` second for stream
verification; `Payload` last so policy can abort mid-stream before anything
leaks downstream.

## Repo layout

| File | Purpose |
|---|---|
| `nitf.dfdl.xsd`, `nitf_common_types.dfdl.xsd`, `nitf_extension_types.dfdl.xsd` | Forked Owl/Tresys NITF DFDL schemas (import paths localized) |
| `jpeg.dfdl.xsd` | Dependency of `nitf_extension_types` (JFIF-compressed imagery) |
| `nitf_envelope.xsd` | CDS envelope schema (our own) |
| `nitf_send.py` | Low-side wrapper: `daffodil parse` → envelope XML |
| `nitf_recv.py` | High-side wrapper: envelope XML → `daffodil unparse` |

## Prerequisites

- **JDK 8+** (Temurin recommended)
  ```
  brew install --cask temurin
  ```
- **Apache Daffodil 4.1.0 (binary distribution)** from
  <https://daffodil.apache.org/releases/4.1.0/> — extract and add `bin/` to
  your `PATH`:
  ```
  export PATH="/path/to/apache-daffodil-4.1.0-bin/bin:$PATH"
  ```
- **Python 3.9+** (stdlib only; no external packages required)

### Fetching the test corpus

`testData/` is not committed. Pull the NITB baseline samples from
`DFDLSchemas/NITF`:

```
mkdir -p testData && cd testData
curl -fsSL "https://api.github.com/repos/DFDLSchemas/NITF/contents/src/test/resources/ntb-baseline" \
  | python3 -c "import json,sys;[print(f['download_url']) for f in json.load(sys.stdin) if f['name'].lower().endswith('.ntf')]" \
  | xargs -n1 -P8 curl -fsSLO
cd ..
```

## Running the scripts

### Send (low side)

```
python3 nitf_send.py path/to/file.nitf -o envelope.xml
```

Useful flags:
- `--max-binary-size N` — override Daffodil's `maxBinarySizeInBytes`.
  Default is `0` so every non-empty payload goes through our manifest.
  Raise (e.g. `10485760`) to let small payloads stay inline as hex.
- `--keep-workdir` — leave the temp dir in place for inspection.

### Receive (high side)

```
python3 nitf_recv.py -i envelope.xml -o reconstructed.nitf
```

Verifies each blob's SHA-256 against the manifest before invoking
`daffodil unparse`. Fails loud on any mismatch.

### Round-trip smoke test

```
python3 nitf_send.py testData/i_3034f.ntf -o envelope.xml
python3 nitf_recv.py -i envelope.xml -o roundtrip.ntf
cmp testData/i_3034f.ntf roundtrip.ntf && echo OK
```

### Batch test across all samples

```
mkdir -p test_artifacts
for f in testData/*.ntf; do
  name=$(basename "$f" .ntf)
  env="test_artifacts/${name}.envelope.xml"
  out="test_artifacts/${name}.roundtrip.ntf"
  python3 nitf_send.py "$f" -o "$env" 2>/dev/null
  python3 nitf_recv.py -i "$env" -o "$out" 2>/dev/null
  if cmp -s "$f" "$out"; then echo "PASS $name"; else echo "DIFF $name"; fi
done
```

Current corpus status: 36/40 byte-identical. The remaining 4
(`i_3015a`, `i_3025b`, `i_3114e`, `i_3117ax`) also fail a plain
`daffodil parse`/`unparse` with no envelope, i.e., upstream schema
round-trip limitations — not something this project introduces.

## TODO

- [ ] **Large files (>100MB) not handled well.** Both scripts currently
  load the entire envelope into memory (ElementTree DOM + full base64
  string). Needs a streaming rewrite (lxml iterparse or SAX) before any
  real-world NITFs above a few hundred MB are attempted.
- [ ] **NiFi integration.** Production flow is NiFi on both sides:
  `GetFile` → (tar.gz extract) → our `nitf_send.py` → `PutTCP`, and
  `ListenTCP` → our `nitf_recv.py` → `UpdateAttribute` → `PutFile`.
  Need NiFi-friendly wrappers (tar.gz input, explicit output paths).
- [ ] **Envelope XSD validation.** `nitf_envelope.xsd` exists but is not
  yet called by either script for defense-in-depth validation.
- [ ] **Policy / inspection layer.** Draft `nitf_policy.xsl` for the guard
  to enforce classification, TRE allowlists, DES type allowlists, etc.
- [ ] **Upstream schema round-trip gaps.** `FileLength` and
  `FileHeaderLength` are marked TODO in the DFDL schema
  (`nitf.dfdl.xsd:117,124`). Fixing these with `outputValueCalc` would
  recover `i_3114e` and `i_3117ax`.
- [ ] **JPEG round-trip.** `i_3015a` byte-differs after round-trip
  (same size, internal bytes differ). Investigate whether the JFIF DFDL
  schema re-serializes with different marker padding.
- [ ] **TCP transport.** Decide whether NiFi's `PutTCP`/`ListenTCP` fully
  handles transport or whether we need our own socket wrappers.

## License / attribution

- `nitf.dfdl.xsd`, `nitf_common_types.dfdl.xsd`,
  `nitf_extension_types.dfdl.xsd` — original BSD-style license from
  Owl Cyber Defense preserved verbatim; fork modifications by Tyler Finn
  (2026) noted below each upstream header.
- `jpeg.dfdl.xsd` — from the [DFDLSchemas/jpeg](https://github.com/DFDLSchemas/jpeg)
  project. Retain its original license.

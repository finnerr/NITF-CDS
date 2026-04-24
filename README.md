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
| `nitf_envelope.xsd` | CDS envelope schema with strict NITF payload validation (dev-side) |
| `nitf_envelope_lean.xsd` | CDS envelope schema with opaque payload (ship this to the CDS) |
| `samples/sample.envelope.xml` | Known-good envelope for validator smoke-testing |
| `nitf_send.py` / `nitf_recv.py` | Low-/high-side Python wrappers (reference implementation + CLI) |
| `nitf_send.groovy` / `nitf_recv.groovy` | NiFi `ExecuteGroovyScript` ports of the senders/receivers |
| `test/run_roundtrip.groovy` | Single-file harness for the Groovy scripts |
| `test/batch_roundtrip.groovy` | Full-corpus harness for the Groovy scripts |
| `test/nifi_stubs/` | Minimal NiFi interface stubs so the Groovy scripts can run outside NiFi |

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

## NiFi deployment

Production flow uses Apache NiFi on both sides of the CDS, with our
`nitf_send.groovy` / `nitf_recv.groovy` running inside
`ExecuteGroovyScript` processors. The Groovy scripts call the Daffodil
JVM API directly — no subprocess, no CLI, no temp files for XML.

### Required files on the NiFi node

1. **Apache Daffodil 4.1.0 binary distribution.** Everything in its
   `lib/` directory goes onto the processor classpath. Air-gapped:
   carry the `apache-daffodil-4.1.0-bin.tgz` across on approved media
   and unpack to, e.g., `/opt/daffodil-4.1.0`.
2. **The DFDL schemas from this repo**: `nitf.dfdl.xsd`,
   `nitf_common_types.dfdl.xsd`, `nitf_extension_types.dfdl.xsd`,
   `jpeg.dfdl.xsd`. Drop them in a single directory the NiFi user can
   read, e.g., `/opt/nitf-cds/schemas/`.
3. **The Groovy scripts**: `nitf_send.groovy` and `nitf_recv.groovy`
   (one per side, but both are safe to stage together).

No Maven, pip, or internet access required — Daffodil ships every
runtime JAR inside its `lib/` directory, and the Groovy scripts use
only JDK stdlib + Daffodil.

### Processor configuration

For the low-side `ExecuteGroovyScript` processor running
`nitf_send.groovy`:

| Property | Value |
|---|---|
| Script File | `/opt/nitf-cds/nitf_send.groovy` |
| Additional classpath | `/opt/daffodil-4.1.0/lib/*` |
| Dynamic property: `Schema Path` | `/opt/nitf-cds/schemas/nitf.dfdl.xsd` |
| Dynamic property: `Max Binary Size` | `0` (force every payload through the manifest) |

Receive-side is identical except the script is `nitf_recv.groovy` and
you don't need the `Max Binary Size` property.

The first FlowFile through each processor pays a one-time ~3-second
schema compile; subsequent FlowFiles reuse the compiled
`DataProcessor` via a static field. Do not lower the processor's
concurrent-tasks below 1 or you'll lose the cache.

### Suggested flow shapes

Low side:
```
ListFile (source/) → UnpackContent (.tar.gz) → RouteOnAttribute (*.ntf|*.nitf)
  → ExecuteGroovyScript (nitf_send.groovy) → PutTCP (→ CDS)
```

High side:
```
ListenTCP → UpdateAttribute (filename) → ExecuteGroovyScript (nitf_recv.groovy)
  → PutFile (archive/) + PutFile (processing/)
```

### Smoke test before wiring to the CDS

Run the batch harness on the NiFi node (or any JDK-bearing box) to
confirm Daffodil + schemas + Groovy scripts agree with your Java
runtime:

```
groovy -cp "test/nifi_stubs:/opt/daffodil-4.1.0/lib/*" \
  test/batch_roundtrip.groovy testData nitf.dfdl.xsd
```

Expect `36 PASS / 4 DIFF`. The four DIFFs are upstream DFDL round-trip
limitations, documented in the TODO.

### Preparing an air-gapped bundle

One-time, on a connected box, assemble a single directory you can
tarball across:

```
airgap-bundle/
├── apache-daffodil-4.1.0-bin.tgz      # wget from daffodil.apache.org
├── jdk/                                # Temurin tar.gz for your target OS
├── groovy/                             # optional; only if you want the
│                                       # out-of-NiFi batch harness on-box
├── nitf-cds/                           # clone of this repo
└── testData/                           # optional; 40-file corpus for smoke tests
```

Then on the target: unpack Daffodil, install the JDK, stage the repo
files where the NiFi user can read them, set the processor properties
above, start the flow.

## TODO

- [ ] **Large files (>100MB) not handled well.** Both scripts currently
  load the entire envelope into memory (ElementTree DOM + full base64
  string). Needs a streaming rewrite (lxml iterparse or SAX) before any
  real-world NITFs above a few hundred MB are attempted.

## License / attribution

- `nitf.dfdl.xsd`, `nitf_common_types.dfdl.xsd`,
  `nitf_extension_types.dfdl.xsd` — original BSD-style license from
  Owl Cyber Defense preserved verbatim; fork modifications by Tyler Finn
  (2026) noted below each upstream header.
- `jpeg.dfdl.xsd` — from the [DFDLSchemas/jpeg](https://github.com/DFDLSchemas/jpeg)
  project. Retain its original license.

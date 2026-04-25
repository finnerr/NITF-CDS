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

## NiFi Flow Reference — `nitf_nifi` Process Group

The NiFi flow lives in a single process group named **`nitf_nifi`**. It
contains two logical pipelines that share a staging directory
(`TESTING/complete/`) to simulate the CDS hand-off in the lab environment.
In production the two pipelines run on separate NiFi instances on opposite
sides of the CDS; the CDS itself replaces the shared directory.

### Flow topology

```
SEND SIDE (low → CDS)
─────────────────────
GetFile ─► CompressContent ─► UnpackContent ─► RouteOnAttribute
                                                     │ matched
                                                     ▼
                                              ExecuteScript (nitf_send.groovy)
                                                     │ success
                                                     ▼
                                              UpdateAttribute (→ .xml)
                                                     │ success
                                           ┌─────────┴──────────┐
                                           ▼                     ▼
                                    PutFile               PutFile
                                  (complete/)        (complete/archive_xml/)
                                  [send to CDS]      [validation archive]


RECEIVE SIDE (CDS → high)
─────────────────────────
GetFile ─► ExecuteScript (nitf_recv.groovy) ─► UpdateAttribute (→ .ntf) ─► PutFile
(complete/*.xml)                                                         (reconstructed/)
```

The `complete/` directory is the lab stand-in for the CDS TCP connection.
In production: send side `PutTCP` → CDS → recv side `ListenTCP`.

---

### Processor details

#### 1. GetFile — ingest `.tar.gz` bundles (send side)

| Property | Value |
|----------|-------|
| Input Directory | `TESTING/source/` |
| File Filter | `.*\.tar\.gz` |
| Polling Interval | 30 sec |
| Batch Size | 1 |
| Keep Source File | false |
| Recurse Subdirectories | false |
| Ignore Hidden Files | true |
| Minimum File Age | 0 sec |
| Minimum File Size | 0 B |

**Relationships:** `success` → CompressContent. No auto-terminations.

Drop a `.tar.gz` containing one or more NITF files into `TESTING/source/`.
The processor picks up one archive per poll cycle and deletes it from source
after ingestion.

---

#### 2. CompressContent — decompress gzip wrapper

| Property | Value |
|----------|-------|
| Mode | decompress |
| Compression Format | gzip |
| Compression Level | 1 |
| Update Filename | false |

**Relationships:** `success` → UnpackContent. `failure` auto-terminated.

Strips the outer gzip layer from the `.tar.gz`, yielding a raw `.tar` stream.
`Update Filename` is `false` because UnpackContent works on the stream, not
the filename.

---

#### 3. UnpackContent — unpack tar archive

| Property | Value |
|----------|-------|
| Packaging Format | tar |
| File Filter | `.*` |
| Filename Character Set | UTF-8 |
| Allow Stored Entries With Data Descriptor | false |

**Relationships:** `success` → RouteOnAttribute. `original` and `failure`
auto-terminated.

Splits each file inside the tar into its own FlowFile. The `original` (the
tar itself) is auto-terminated; only the extracted members flow forward.

---

#### 4. RouteOnAttribute — filter NITF files

| Property | Value |
|----------|-------|
| Routing Strategy | Route to 'match' if all match |
| Dynamic property: `nitf` | `${filename:toLower():matches('.*\\.(ntf\|nitf)')}` |

**Relationships:** `matched` → ExecuteScript (send). `unmatched`
auto-terminated.

Passes only files whose names end in `.ntf` or `.nitf` (case-insensitive).
Non-NITF files extracted from the tar (e.g. manifests, READMEs) are
silently dropped.

---

#### 5. ExecuteScript — `nitf_send.groovy` (send side)

| Property | Value |
|----------|-------|
| Script Engine | Groovy |
| Script File | `TESTING/nitf_send.groovy` |
| Module Directory | `TESTING/lib` |
| Dynamic property: `Schema Path` | `TESTING/nitf.dfdl.xsd` |
| Dynamic property: `Max Binary Size` | `0` |
| Concurrent Tasks | 1 |
| Run Duration | 0 ms |

**Relationships:** `success` → UpdateAttribute (send). `failure`
auto-terminated (errors logged as bulletins).

Calls Daffodil JAPI to parse the binary NITF into an XML infoset, extracts
binary blobs (image data, TREs) to base64 with SHA-256 hashes, and wraps
everything in a `<NitfMessage>` envelope. `Max Binary Size = 0` forces every
binary payload through the blob manifest regardless of size. The compiled
Daffodil `DataProcessor` is cached in a static field after the first FlowFile;
subsequent files reuse it with no recompile overhead.

> **Concurrent Tasks must remain 1.** The static `DataProcessor` cache uses
> a double-checked lock; concurrent execution would race the cache population
> on cold start.

---

#### 6. UpdateAttribute — rename to `.xml` (send side)

| Property | Value |
|----------|-------|
| Dynamic property: `filename` | `${filename:substringBeforeLast('.')}.xml` |
| Store State | Do not store state |

**Relationships:** `success` → both PutFile processors (fanout).

Renames the FlowFile from `image.ntf` → `image.xml` before writing to disk,
so the downstream GetFile (recv side) and the archive can filter by `.xml`.

---

#### 7. PutFile — write envelope to staging area (send side)

| Property | Value |
|----------|-------|
| Directory | `TESTING/complete/` |
| Conflict Resolution Strategy | replace |
| Create Missing Directories | true |

**Relationships:** `success` and `failure` both auto-terminated.

Writes the `<NitfMessage>` XML envelope to `complete/`. This directory is
polled by the recv-side GetFile, simulating what the CDS would deliver to the
high side. `replace` prevents stalls when re-processing the same file during
development.

---

#### 8. PutFile — write to validation archive (send side)

| Property | Value |
|----------|-------|
| Directory | `TESTING/complete/archive_xml/` |
| Conflict Resolution Strategy | fail |
| Create Missing Directories | true |

**Relationships:** `success` and `failure` both auto-terminated.

Writes a permanent copy of each envelope to `archive_xml/`. This is the
corpus that `validate_all.sh` runs the XSD validators against. `fail` (not
`replace`) is intentional — a collision here means the same file was processed
twice, which is worth knowing about.

---

#### 9. GetFile — ingest XML envelopes (recv side)

| Property | Value |
|----------|-------|
| Input Directory | `TESTING/complete/` |
| File Filter | `.*\.xml` |
| Polling Interval | 30 sec |
| Batch Size | 1 |
| Keep Source File | false |
| Recurse Subdirectories | false |
| Ignore Hidden Files | true |

**Relationships:** `success` → ExecuteScript (recv).

Reads the XML envelopes that the send side (or a real CDS) deposited in
`complete/`. `Keep Source File = false` deletes each envelope after ingestion,
preventing the recv GetFile from picking up the same file twice.

---

#### 10. ExecuteScript — `nitf_recv.groovy` (recv side)

| Property | Value |
|----------|-------|
| Script Engine | Groovy |
| Script File | `TESTING/nitf_recv.groovy` |
| Module Directory | `TESTING/lib` |
| Dynamic property: `Schema Path` | `TESTING/nitf.dfdl.xsd` |
| Dynamic property: `Max Binary Size` | `0` |
| Concurrent Tasks | 1 |
| Run Duration | 0 ms |

**Relationships:** `success` → UpdateAttribute (recv). `failure`
auto-terminated.

Extracts the inline `<Payload>` XML and `<Blobs>` base64 data, verifies each
blob's SHA-256 against the `<Manifest>`, writes blobs to temp files, rewrites
`cds:blob:N` URIs in the payload to `file://` URIs, and calls Daffodil
`unparse` to reconstruct the binary NITF. The same `DataProcessor` cache
pattern applies (static field, Concurrent Tasks = 1).

---

#### 11. UpdateAttribute — rename to `.ntf` (recv side)

| Property | Value |
|----------|-------|
| Dynamic property: `filename` | `${filename:substringBeforeLast('.')}.ntf` |
| Store State | Do not store state |

**Relationships:** `success` → PutFile (recv).

Restores the `.ntf` extension so the output file is recognizable as a NITF
binary rather than being named `image.xml`.

---

#### 12. PutFile — write reconstructed NITF (recv side)

| Property | Value |
|----------|-------|
| Directory | `TESTING/reconstructed/` |
| Conflict Resolution Strategy | replace |
| Create Missing Directories | false |

**Relationships:** `success` and `failure` both auto-terminated.

Final output: reconstructed binary NITF in `TESTING/reconstructed/`. Compare
against the original with `cmp` to verify byte-for-byte round-trip fidelity.

---

### Directory layout referenced by the flow

```
TESTING/
├── source/           ← drop .tar.gz bundles here (send GetFile picks up)
├── complete/         ← send side writes XML here; recv GetFile picks up
│   └── archive_xml/  ← permanent copy of every envelope (XSD validation corpus)
├── reconstructed/    ← recv side writes binary NITF here
└── lib/              ← Daffodil runtime JARs (Module Directory for both scripts)
```

---

## Airgapped RHEL Deployment

This section covers standing up the full NiFi pipeline on a RHEL 9 host
with no external internet access. Assumes standard RHEL repos (BaseOS +
AppStream) are available via an internal mirror or subscription, which is
typical for classified network enclaves.

### What must be burned to approved media

These are not available from standard RHEL repos and must be downloaded
on a connected machine and transferred in:

| Item | Source | Notes |
|------|--------|-------|
| **Apache NiFi** (latest binary `.tar.gz`) | `https://nifi.apache.org/download/` | Take the binary distribution, not source |
| **Apache Daffodil** (latest binary `.tar.gz`) | `https://daffodil.apache.org/releases/` | Take the binary distribution; `lib/` is what NiFi needs |
| **This repo** (`.zip` or `git bundle`) | `https://github.com/finnerr/NITF-CDS` | `git bundle create nitf-cds.bundle --all` on a connected box |

### What standard RHEL 9 repos provide

Install these normally via `dnf` once the system has repo access:

```bash
# Java 21 runtime and compiler (NiFi 2.x and Daffodil both require Java 21+)
dnf install -y java-21-openjdk java-21-openjdk-devel

# XML validation tools (xmllint smoke-test of the XSD)
dnf install -y libxml2

# Xerces-C (for the C++ schema validator utility, optional but recommended)
dnf install -y xerces-c xerces-c-devel

# C++ compiler and make (to build the validate binary from validate.cpp)
dnf install -y gcc-c++ make
```

> **Java note:** You do *not* need to burn a JDK to disc if the host has
> access to the internal RHEL repo mirror. `java-21-openjdk` is in the
> standard AppStream repo. Verify with `dnf list available java-21-openjdk`.

### Installation sequence

```bash
# 1. Unpack NiFi
tar -xzf apache-nifi-X.Y.Z-bin.tar.gz -C /opt
ln -s /opt/apache-nifi-X.Y.Z /opt/nifi

# 2. Unpack Daffodil
tar -xzf apache-daffodil-X.Y.Z-bin.tar.gz -C /opt
ln -s /opt/apache-daffodil-X.Y.Z-bin /opt/daffodil

# 3. Stage project files
mkdir -p /opt/nitf-cds/schemas /opt/nitf-cds/scripts /opt/nitf-cds/lib
# DFDL schemas (needed by Daffodil at parse time)
cp nitf.dfdl.xsd nitf_common_types.dfdl.xsd \
   nitf_extension_types.dfdl.xsd jpeg.dfdl.xsd \
   /opt/nitf-cds/schemas/
# Groovy scripts
cp TESTING/nitf_send.groovy TESTING/nitf_recv.groovy /opt/nitf-cds/scripts/
# Daffodil runtime JARs — NiFi classpath needs all of these
cp /opt/daffodil/lib/*.jar /opt/nitf-cds/lib/

# 4. Point NiFi at Java 21
# In /opt/nifi/conf/bootstrap.conf, set:
#   java.home=/usr/lib/jvm/java-21-openjdk
# Or export JAVA_HOME before starting NiFi.

# 5. Create a dedicated service account (do not run NiFi as root)
useradd -r -d /opt/nifi -s /sbin/nologin nifi
chown -R nifi:nifi /opt/nifi /opt/nitf-cds

# 6. Start NiFi (check logs for the generated HTTPS credentials on first run)
/opt/nifi/bin/nifi.sh start
grep "Generated Username\|Generated Password" /opt/nifi/logs/nifi-app.log
```

### NiFi 2.x specifics

- **HTTPS only.** NiFi 2.x does not offer HTTP. It generates a self-signed
  cert at first start. In a classified enclave this is typically acceptable;
  if your accreditor requires a CA-signed cert, use `nifi-toolkit` (bundled
  in the NiFi distribution) to generate a CSR before first start.
- **First-start credentials.** A random username and password are written to
  `logs/nifi-app.log` on first start. Capture these immediately; they are
  shown only once. Change them via `bin/nifi.sh set-single-user-credentials`.
- **Groovy is bundled.** NiFi ships with Groovy inside the scripting NAR.
  No separate Groovy installation is needed.
- **ExecuteGroovyScript processor.** Use `ExecuteScript` and select
  `Groovy` as the engine, *not* a separate processor type.

### Processor configuration (NiFi 2.x)

For the send-side `ExecuteScript` processor:

| Property | Value |
|----------|-------|
| Script Engine | Groovy |
| Script File | `/opt/nitf-cds/scripts/nitf_send.groovy` |
| Module Directory | `/opt/nitf-cds/lib` |
| Dynamic property: `Schema Path` | `/opt/nitf-cds/schemas/nitf.dfdl.xsd` |
| Dynamic property: `Max Binary Size` | `0` |

Receive side: same but point Script File at `nitf_recv.groovy`; omit
`Max Binary Size`.

### CDS schema deployment

The CDS appliance validates the XML it receives. Deploy both XSD files to
the CDS (exact path depends on the device; consult the vendor):

```
nitf_envelope_lean.xsd   ← root schema; give this path to the CDS policy config
nitf_infoset_v21.xsd     ← must be co-located in the same directory
```

The root schema imports the infoset schema by relative filename. If the CDS
cannot resolve relative `xs:import`, the two files must be concatenated into
a single flat schema — contact the vendor before deployment.

### SELinux on RHEL 9

RHEL 9 runs SELinux enforcing by default. NiFi writing to temp directories
and reading schema files may be blocked. Quickest resolution:

```bash
# Label directories NiFi owns so SELinux allows read/write
semanage fcontext -a -t usr_t "/opt/nifi(/.*)?"
semanage fcontext -a -t usr_t "/opt/nitf-cds(/.*)?"
restorecon -Rv /opt/nifi /opt/nitf-cds
```

If you see `Permission denied` in `nifi-app.log` after labeling, check
`ausearch -m avc -ts recent` to find the specific denial and add a targeted
policy rather than setting permissive mode for the whole host.

### Firewall

```bash
# NiFi web UI / API (HTTPS)
firewall-cmd --permanent --add-port=8443/tcp
# CDS send port (adjust to match your CDS TCP listener)
firewall-cmd --permanent --add-port=<CDS_PORT>/tcp
firewall-cmd --reload
```

### Time synchronization

NiFi TLS certificates carry validity windows. On an airgapped host, ensure
the system clock is synchronized to an internal stratum-1 source (GPS clock
or internal NTP server). Configure via `/etc/chrony.conf`:

```
server <internal-ntp-host> iburst
```

A clock skew of more than a few minutes will cause TLS handshake failures
between NiFi nodes or between NiFi and the CDS.

### Smoke-test before connecting to the CDS

```bash
cd /opt/nitf-cds/validate   # after building validate binary with make
./tools/validate_all.sh
# Expected: 40 / 40 pass
```

If fewer than 40 pass, check that both XSD files are present and that the
`xmllint` binary (`libxml2` package) is on PATH.

---

## License / attribution

- `nitf.dfdl.xsd`, `nitf_common_types.dfdl.xsd`,
  `nitf_extension_types.dfdl.xsd` — original BSD-style license from
  Owl Cyber Defense preserved verbatim; fork modifications by Tyler Finn
  (2026) noted below each upstream header.
- `jpeg.dfdl.xsd` — from the [DFDLSchemas/jpeg](https://github.com/DFDLSchemas/jpeg)
  project. Retain its original license.

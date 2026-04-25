# NITF over CDS

Send NITF (National Imagery Transmission Format) files across a streaming
cross-domain solution (CDS) that only accepts a single XML document per
message and validates against an XSD.

We need a way to:

1. Convert NITF to a form the CDS can inspect.
2. Preserve round-trip fidelity so the high side reconstructs the original NITF byte-for-byte.
3. Bind the approved metadata to the actual pixel bytes so an attacker cannot swap the payload after inspection.

## Approach

Apache Daffodil parses the binary NITF into an XML infoset using a forked copy of the Owl Cyber Defense NITF DFDL schema. Binary blobs (image data, TREs) are extracted, base64-encoded with SHA-256 hashes, and the whole thing is wrapped in a single `<NitfMessage>` envelope the CDS can validate.

```
LOW SIDE (NiFi)                     CDS                     HIGH SIDE (NiFi)
───────────────                ─────────────                ────────────────
*.ntf (.tar.gz)                XSD validation               reconstructed.ntf
  │                            · nitf_envelope_lean.xsd           ▲
  │ UnpackContent               · nitf_infoset_v21.xsd            │
  │ RouteOnAttribute                                              │
  ▼                                                               │
nitf_send.groovy                                         nitf_recv.groovy
· Daffodil parse NITF → XML                           · verify blob SHA-256
· blobs → base64 + SHA-256                            · restore blobs to disk
· inline XML into <Payload>                           · rewrite cds:blob:N URIs
· build <NitfMessage> envelope                        · Daffodil unparse → .ntf
  │                                                               ▲
  └──────────── TCP ───── <NitfMessage> ────── TCP ───────────────┘
```

Element order in the envelope matters: `Manifest` first (guard knows expected hashes before bytes arrive), `Blobs` second (stream verification), `Payload` last (policy can abort before anything leaks downstream).

## Repo layout

| File | Purpose |
|------|---------|
| `nitf.dfdl.xsd`, `nitf_common_types.dfdl.xsd`, `nitf_extension_types.dfdl.xsd` | Forked Owl/Tresys NITF DFDL schemas (import paths localized) |
| `jpeg.dfdl.xsd` | JFIF dependency of `nitf_extension_types` |
| `validate/nitf_envelope_lean.xsd` | Root CDS envelope schema — give this path to the CDS |
| `validate/nitf_infoset_v21.xsd` | Strict NITF 2.1 infoset schema — must be co-located with envelope schema on the CDS |
| `nitf_send.groovy` | NiFi ExecuteScript processor — low side (NITF → envelope) |
| `nitf_recv.groovy` | NiFi ExecuteScript processor — high side (envelope → NITF) |
| `validate/validate.cpp` + `validate/Makefile` | Xerces-C SAX2 schema validator (C++) |
| `validate/tools/validate_all.sh` | Runs xmllint + Xerces-C against all archive_xml output |

## NiFi deployment

Both sides run Apache NiFi with `nitf_send.groovy` / `nitf_recv.groovy` inside `ExecuteScript` processors. The Groovy scripts call Daffodil's JAPI directly — no subprocess, no CLI.

Each NiFi node needs:
- Daffodil binary distribution — all JARs from its `lib/` directory on the processor's Module Directory
- DFDL schemas (`nitf.dfdl.xsd` and its three dependencies) in a directory the NiFi service account can read
- The Groovy scripts staged on disk (not uploaded inline)

Key processor properties (`ExecuteScript`, Groovy engine):

| Property | Send side | Recv side |
|----------|-----------|-----------|
| Script File | `nitf_send.groovy` | `nitf_recv.groovy` |
| Module Directory | path to Daffodil `lib/` | same |
| `Schema Path` (dynamic) | path to `nitf.dfdl.xsd` | same |
| `Max Binary Size` (dynamic) | `0` | — |
| Concurrent Tasks | **1** (required) | **1** (required) |

> Concurrent Tasks **must** remain 1 on both processors. The scripts enforce this with an `AtomicInteger` guard and will fail-fast if violated. The send script manipulates a JVM-global property (`user.dir`) for Daffodil blob storage; concurrent execution would corrupt blob paths.

> **Memory**: peak heap usage is ~3× the raw NITF size (parse buffer + rewritten XML + envelope). Size NiFi's JVM heap in `conf/bootstrap.conf` to accommodate the largest expected file × 3.

See **NiFi Flow Reference** below for the complete processor-by-processor configuration of the current lab flow.

---

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
cp nitf.dfdl.xsd nitf_common_types.dfdl.xsd \
   nitf_extension_types.dfdl.xsd jpeg.dfdl.xsd \
   /opt/nitf-cds/schemas/
cp TESTING/nitf_send.groovy TESTING/nitf_recv.groovy /opt/nitf-cds/scripts/
cp /opt/daffodil/lib/*.jar /opt/nitf-cds/lib/

# 4. Point NiFi at Java 21
# In /opt/nifi/conf/bootstrap.conf, set:
#   java.home=/usr/lib/jvm/java-21-openjdk

# 5. Create a dedicated service account (do not run NiFi as root)
useradd -r -d /opt/nifi -s /sbin/nologin nifi
chown -R nifi:nifi /opt/nifi /opt/nitf-cds

# 6. Start NiFi (check logs for the generated HTTPS credentials on first run)
/opt/nifi/bin/nifi.sh start
grep "Generated Username\|Generated Password" /opt/nifi/logs/nifi-app.log
```

### NiFi 2.x specifics

- **HTTPS only.** NiFi 2.x does not offer HTTP. It generates a self-signed cert at first start.
- **First-start credentials.** Random username and password are written to `logs/nifi-app.log` on first start — shown only once. Change them via `bin/nifi.sh set-single-user-credentials`.
- **Groovy is bundled.** NiFi ships with Groovy inside the scripting NAR. No separate Groovy installation needed.

### Processor configuration (NiFi 2.x)

| Property | Value |
|----------|-------|
| Script Engine | Groovy |
| Script File | `/opt/nitf-cds/scripts/nitf_send.groovy` (or `nitf_recv.groovy`) |
| Module Directory | `/opt/nitf-cds/lib` |
| Dynamic property: `Schema Path` | `/opt/nitf-cds/schemas/nitf.dfdl.xsd` |
| Dynamic property: `Max Binary Size` | `0` (send side only) |

### CDS schema deployment

Deploy both XSD files to the CDS (exact path depends on the device):

```
nitf_envelope_lean.xsd   ← root schema; give this path to the CDS policy config
nitf_infoset_v21.xsd     ← must be co-located in the same directory
```

The root schema imports the infoset schema by relative filename. If the CDS
cannot resolve relative `xs:import`, the two files must be concatenated into
a single flat schema — contact the vendor before deployment.

### SELinux on RHEL 9

```bash
semanage fcontext -a -t usr_t "/opt/nifi(/.*)?"
semanage fcontext -a -t usr_t "/opt/nitf-cds(/.*)?"
restorecon -Rv /opt/nifi /opt/nitf-cds
```

If you still see `Permission denied` in `nifi-app.log`, check `ausearch -m avc -ts recent` for the specific denial.

### Firewall

```bash
firewall-cmd --permanent --add-port=8443/tcp        # NiFi HTTPS
firewall-cmd --permanent --add-port=<CDS_PORT>/tcp  # CDS TCP
firewall-cmd --reload
```

### Time synchronization

NiFi TLS certificates depend on correct time. Configure chrony to an internal stratum-1 source in `/etc/chrony.conf`:

```
server <internal-ntp-host> iburst
```

Clock skew of more than a few minutes causes TLS handshake failures.

### Schema validation smoke test

```bash
cd /opt/nitf-cds/validate  # after building with make
./tools/validate_all.sh
# Expected: 40 / 40 pass
```

---

## License / attribution

- `nitf.dfdl.xsd`, `nitf_common_types.dfdl.xsd`,
  `nitf_extension_types.dfdl.xsd` — original BSD-style license from
  Owl Cyber Defense preserved verbatim; fork modifications by Tyler Finn
  (2026) noted below each upstream header.
- `jpeg.dfdl.xsd` — from the [DFDLSchemas/jpeg](https://github.com/DFDLSchemas/jpeg)
  project. Retain its original license.

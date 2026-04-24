#!/usr/bin/env python3
# Copyright (c) 2026 Tyler Finn.
# Part of the NITF-over-CDS project.
"""
High-side receiver. Reverse of nitf_send.py.

Pipeline:
  1. Read envelope XML from stdin (or --in).
  2. For each <BlobData>: base64-decode, verify SHA-256 against the manifest
     entry with the same @index, and write the bytes to a temp file.
  3. Walk the Payload/NITF subtree, rewrite every BlobPayload whose text is
     "cds:blob:N" back to the temp file path for index N.
  4. Write the rewritten NITF XML to a temp file and invoke `daffodil
     unparse` to reconstruct the original NITF.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.etree import ElementTree as ET

NITF_NS = "urn:nitf:2.1"
NITFC_NS = "urn:nitfCommonTypes"
CDS_NS = "urn:cds:nitf:1"

BLOB_PAYLOAD_QNAME = "BlobPayload"


def parse_envelope(src) -> ET.ElementTree:
    return ET.parse(src)


def extract_manifest(envelope: ET.Element) -> dict[int, tuple[int, str]]:
    """Return {index: (size, sha256)} from the Manifest subtree."""
    out: dict[int, tuple[int, str]] = {}
    manifest = envelope.find(f"{{{CDS_NS}}}Manifest")
    if manifest is None:
        return out
    for blob in manifest.findall(f"{{{CDS_NS}}}Blob"):
        idx = int(blob.attrib["index"])
        size = int(blob.attrib["size"])
        digest = blob.attrib["sha256"]
        out[idx] = (size, digest)
    return out


def materialize_blobs(envelope: ET.Element, manifest: dict[int, tuple[int, str]],
                       work: Path) -> dict[int, Path]:
    """Decode BlobData into files under `work`, verifying each against manifest."""
    out: dict[int, Path] = {}
    blobs = envelope.find(f"{{{CDS_NS}}}Blobs")
    if blobs is None:
        if manifest:
            raise RuntimeError("manifest lists blobs but envelope has no <Blobs> section")
        return out
    for bd in blobs.findall(f"{{{CDS_NS}}}BlobData"):
        idx = int(bd.attrib["index"])
        if idx not in manifest:
            raise RuntimeError(f"BlobData index {idx} not in manifest")
        expected_size, expected_sha = manifest[idx]
        raw = base64.b64decode((bd.text or "").strip(), validate=True)
        if len(raw) != expected_size:
            raise RuntimeError(
                f"blob {idx} size mismatch: got {len(raw)}, manifest says {expected_size}"
            )
        actual_sha = hashlib.sha256(raw).hexdigest()
        if actual_sha != expected_sha:
            raise RuntimeError(
                f"blob {idx} sha256 mismatch: got {actual_sha}, manifest says {expected_sha}"
            )
        path = work / f"blob_{idx:06d}.bin"
        path.write_bytes(raw)
        out[idx] = path
    missing = set(manifest) - set(out)
    if missing:
        raise RuntimeError(f"manifest references blobs with no BlobData: {sorted(missing)}")
    return out


_BLOBPAYLOAD_RE = re.compile(
    r"(<BlobPayload[^>]*>)([^<]*)(</BlobPayload>)"
)


def rewrite_payload_uris_text(payload_xml: str, blob_files: dict[int, Path]) -> str:
    """Replace cds:blob:N URIs with the local file: URIs Daffodil expects."""

    def replace(match: re.Match[str]) -> str:
        open_tag, text, close_tag = match.group(1), match.group(2).strip(), match.group(3)
        if not text.startswith("cds:blob:"):
            return match.group(0)
        try:
            idx = int(text.split(":", 2)[2])
        except (IndexError, ValueError) as e:
            raise RuntimeError(f"malformed cds blob URI: {text!r}") from e
        if idx not in blob_files:
            raise RuntimeError(f"payload references cds:blob:{idx} but no such blob")
        return f"{open_tag}{blob_files[idx].as_uri()}{close_tag}"

    return _BLOBPAYLOAD_RE.sub(replace, payload_xml)


_PAYLOAD_RE = re.compile(
    r"<(?:[A-Za-z_][\w.-]*:)?Payload\b[^>]*>(.*)</(?:[A-Za-z_][\w.-]*:)?Payload>",
    re.DOTALL,
)


def extract_payload_inner_xml(envelope_bytes: bytes) -> str:
    """Return the raw inner XML of <Payload> as a text fragment (with decl)."""
    text = envelope_bytes.decode("utf-8")
    m = _PAYLOAD_RE.search(text)
    if not m:
        raise RuntimeError("no <Payload> element found in envelope")
    return m.group(1).strip()


def run_daffodil_unparse(schema: Path, xml_in: Path, nitf_out: Path, cwd: Path) -> None:
    cmd = [
        "daffodil", "unparse",
        "-s", str(schema),
        "-o", str(nitf_out),
        str(xml_in),
    ]
    subprocess.run(cmd, cwd=cwd, check=True)


def main() -> int:
    ap = argparse.ArgumentParser(description="CDS envelope XML -> NITF (high side).")
    ap.add_argument("-i", "--in", dest="inp", type=Path,
                    help="input envelope XML (default: stdin)")
    ap.add_argument("-o", "--out", type=Path, required=True,
                    help="output NITF file")
    ap.add_argument("-s", "--schema", type=Path,
                    default=Path(__file__).parent / "nitf.dfdl.xsd",
                    help="DFDL schema root (default: nitf.dfdl.xsd next to this script)")
    ap.add_argument("--keep-workdir", action="store_true")
    args = ap.parse_args()

    if args.inp:
        envelope_bytes = args.inp.read_bytes()
    else:
        envelope_bytes = sys.stdin.buffer.read()

    envelope_root = ET.fromstring(envelope_bytes)
    if envelope_root.tag != f"{{{CDS_NS}}}NitfMessage":
        raise RuntimeError(f"unexpected root element: {envelope_root.tag}")

    work = Path(tempfile.mkdtemp(prefix="nitf_recv_"))
    try:
        manifest = extract_manifest(envelope_root)
        blob_files = materialize_blobs(envelope_root, manifest, work)

        inner = extract_payload_inner_xml(envelope_bytes)
        inner = rewrite_payload_uris_text(inner, blob_files)

        payload_xml = work / "payload.xml"
        payload_xml.write_text(
            '<?xml version="1.0" encoding="UTF-8"?>\n' + inner + "\n",
            encoding="utf-8",
        )
        run_daffodil_unparse(args.schema, payload_xml, args.out.resolve(), work)
    finally:
        if not args.keep_workdir:
            shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

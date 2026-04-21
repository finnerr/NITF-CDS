#!/usr/bin/env python3
# Copyright (c) 2026 Tyler Finn.
# Part of the NITF-over-CDS project.
"""
Low-side sender.

Pipeline:
  1. Run `daffodil parse` on the input NITF. Daffodil emits an XML document
     plus zero or more blob files (when a payload exceeds maxBinarySizeInBytes,
     Daffodil writes bytes to disk and references them by URI in the XML).
  2. Walk the XML, find every BlobPayload element (anyURI referring to a
     local blob file), read the file, compute SHA-256, base64-encode the
     bytes, and rewrite the URI to "cds:blob:N".
  3. Emit a single <NitfMessage> envelope to stdout (or --out).

Output is what crosses the CDS. See PROJECT.md.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlparse
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape as xml_escape

NITF_NS = "urn:nitf:2.1"
NITFC_NS = "urn:nitfCommonTypes"
CDS_NS = "urn:cds:nitf:1"

# BlobPayload is declared inside a complexType in a schema whose
# elementFormDefault="unqualified", so in the instance document it lives
# in no namespace (not urn:nitfCommonTypes).
BLOB_PAYLOAD_QNAME = "BlobPayload"


def run_daffodil_parse(schema: Path, nitf: Path, work: Path,
                        max_binary_size: int | None) -> Path:
    """Invoke `daffodil parse` and return the path to the resulting XML."""
    xml_out = work / "parsed.xml"
    # Daffodil writes blob files relative to CWD by default; run from `work`
    # so they land in a predictable place we can clean up.
    cmd = ["daffodil", "parse", "-s", str(schema)]
    if max_binary_size is not None:
        cmd += ["-D{urn:nitf:2.1}maxBinarySizeInBytes=" + str(max_binary_size)]
    cmd += ["-o", str(xml_out), str(nitf)]
    subprocess.run(cmd, cwd=work, check=True)
    return xml_out


def blob_uri_to_path(uri: str, base: Path) -> Path | None:
    """Resolve a Daffodil BlobPayload URI to a local file path.

    Daffodil typically emits file: URIs or relative paths. Return None for
    anything that does not resolve to an existing local file so the caller
    can decide whether that is an error.
    """
    parsed = urlparse(uri)
    if parsed.scheme in ("", "file"):
        path_str = parsed.path or uri
        p = Path(path_str)
        if not p.is_absolute():
            p = base / p
        return p if p.exists() else None
    return None


_BLOBPAYLOAD_RE = re.compile(
    r"(<BlobPayload[^>]*>)([^<]*)(</BlobPayload>)"
)


def build_envelope_xml(parsed_xml: Path, blob_base: Path) -> bytes:
    """Read Daffodil's XML, relocate blobs, return a complete envelope document.

    We splice Daffodil's XML text as-is into <Payload> rather than
    reparenting an ElementTree, because ElementTree's namespace serialization
    absorbs no-namespace children into any ancestor default namespace -
    which breaks Daffodil unparse on the receiving end.
    """
    raw = parsed_xml.read_text(encoding="utf-8")
    # Strip XML declaration so we can embed the body inside the envelope.
    body = re.sub(r"^\s*<\?xml[^?]*\?>\s*", "", raw, count=1)

    manifest_entries: list[tuple[int, int, str]] = []
    blob_b64s: list[tuple[int, str]] = []

    def replace(match: re.Match[str]) -> str:
        idx = len(manifest_entries)
        open_tag, uri_text, close_tag = match.group(1), match.group(2), match.group(3)
        blob_path = blob_uri_to_path(uri_text.strip(), blob_base)
        if blob_path is None:
            raise RuntimeError(
                f"BlobPayload URI {uri_text!r} does not resolve to a local file"
            )
        data = blob_path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        manifest_entries.append((idx, len(data), digest))
        blob_b64s.append((idx, base64.b64encode(data).decode("ascii")))
        return f"{open_tag}cds:blob:{idx}{close_tag}"

    body_rewritten = _BLOBPAYLOAD_RE.sub(replace, body)

    parts: list[str] = []
    parts.append('<?xml version="1.0" encoding="UTF-8"?>')
    parts.append('<NitfMessage xmlns="urn:cds:nitf:1" version="1">')
    parts.append("  <Manifest>")
    for idx, size, digest in manifest_entries:
        parts.append(
            f'    <Blob index="{idx}" size="{size}" sha256="{digest}"/>'
        )
    parts.append("  </Manifest>")
    if blob_b64s:
        parts.append("  <Blobs>")
        for idx, b64 in blob_b64s:
            parts.append(f'    <BlobData index="{idx}">{b64}</BlobData>')
        parts.append("  </Blobs>")
    parts.append("  <Payload>")
    parts.append(body_rewritten.rstrip())
    parts.append("  </Payload>")
    parts.append("</NitfMessage>")
    parts.append("")
    return "\n".join(parts).encode("utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description="NITF -> CDS envelope XML (low side).")
    ap.add_argument("nitf", type=Path, help="input NITF file")
    ap.add_argument("-s", "--schema", type=Path,
                    default=Path(__file__).parent / "nitf.dfdl.xsd",
                    help="DFDL schema root (default: nitf.dfdl.xsd next to this script)")
    ap.add_argument("-o", "--out", type=Path,
                    help="output envelope XML (default: stdout)")
    ap.add_argument("--keep-workdir", action="store_true",
                    help="do not delete the daffodil work directory on exit")
    ap.add_argument("--max-binary-size", type=int, default=0,
                    help="override nitf:maxBinarySizeInBytes (bytes). Default "
                         "is 0, which forces every non-empty payload to BLOB "
                         "mode so the envelope manifest covers all bytes "
                         "uniformly. Set higher (e.g. 10485760) to let small "
                         "payloads stay inline as hex.")
    args = ap.parse_args()

    work = Path(tempfile.mkdtemp(prefix="nitf_send_"))
    try:
        parsed_xml = run_daffodil_parse(args.schema, args.nitf.resolve(), work,
                                         args.max_binary_size)
        envelope_bytes = build_envelope_xml(parsed_xml, work)
        if args.out:
            args.out.write_bytes(envelope_bytes)
        else:
            sys.stdout.buffer.write(envelope_bytes)
    finally:
        if not args.keep_workdir:
            shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

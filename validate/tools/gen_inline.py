#!/usr/bin/env python3
# Copyright (c) 2026 Tyler Finn.
# Part of the NITF-over-CDS project.
#
# Converts every archived NitfMessage (with base64 Payload) under
# complete/archive_xml/ into an inline-payload version under
# validate/samples/, so the validators can exercise the full
# envelope + infoset schema pair.
#
# Matches the groovy send-side transform: strip xml decl off the
# decoded infoset, inject xmlns="" on the NITF root, inline.

import base64, os, re, sys

ARCHIVE = '/finn_share/Development/NITF_CDS/TESTING/complete/archive_xml'
SAMPLES = '/finn_share/Development/NITF_CDS/TESTING/validate/samples'

os.makedirs(SAMPLES, exist_ok=True)

count = 0
for name in sorted(os.listdir(ARCHIVE)):
    if not name.endswith('.xml'):
        continue
    src = os.path.join(ARCHIVE, name)
    dst = os.path.join(SAMPLES, name.replace('.xml', '_inline.xml'))
    x = open(src).read()
    m = re.search(r'(<Payload>)(.*?)(</Payload>)', x, re.S)
    if not m:
        print(f'SKIP {name}: no <Payload>')
        continue
    b = re.sub(r'\s+', '', m.group(2))
    try:
        decoded = base64.b64decode(b).decode('utf-8')
    except Exception as e:
        print(f'SKIP {name}: base64 decode failed ({e})')
        continue
    # strip the inner xml declaration Daffodil emits
    decoded = re.sub(r'^\s*<\?xml[^?]*\?>\s*', '', decoded)
    # inject xmlns="" on the NITF root (matches groovy send-side)
    decoded = decoded.replace(
        '<nitf:NITF xmlns:nitf="urn:nitf:2.1"',
        '<nitf:NITF xmlns:nitf="urn:nitf:2.1" xmlns=""', 1)
    inlined = x[:m.start(2)] + '\n    ' + decoded + '\n  ' + x[m.end(2):]
    open(dst, 'w').write(inlined)
    count += 1

print(f'wrote {count} inline samples to {SAMPLES}')

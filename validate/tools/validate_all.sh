#!/usr/bin/env bash
# Copyright (c) 2026 Tyler Finn.
# Part of the NITF-over-CDS project.
#
# Runs both xmllint and the xerces-c validator against every inline
# sample, prints a compact pass/fail summary, and shows the first
# few error lines for each failing file so it's easy to spot the
# next schema gap.

set -u
cd "$(dirname "$0")/.."

XSD=nitf_envelope_lean.xsd
VALIDATE=./validate
SAMPLES=/finn_share/Development/NITF_CDS/TESTING/complete/archive_xml

pass=0
fail_list=()

for f in "$SAMPLES"/*.xml; do
    name=$(basename "$f" .xml)
    xmllint_out=$(xmllint --schema "$XSD" "$f" --noout 2>&1)
    xmllint_rc=$?
    xerces_out=$("$VALIDATE" "$XSD" "$f" 2>&1)
    xerces_rc=$?
    if [ $xmllint_rc -eq 0 ] && [ $xerces_rc -eq 0 ]; then
        pass=$((pass+1))
    else
        fail_list+=("$name")
    fi
done

total=$(ls "$SAMPLES"/*.xml | wc -l)
echo "===== $pass / $total pass ====="
if [ ${#fail_list[@]} -gt 0 ]; then
    echo ""
    echo "FAILING (${#fail_list[@]}):"
    for n in "${fail_list[@]}"; do
        echo "  $n"
    done
    echo ""
    echo "----- first failure detail -----"
    first="${fail_list[0]}"
    echo "### $first ###"
    xmllint --schema "$XSD" "$SAMPLES/${first}.xml" --noout 2>&1 | head -5
    echo "---"
    "$VALIDATE" "$XSD" "$SAMPLES/${first}.xml" 2>&1 | grep -E '(error|fail)' | head -5
fi

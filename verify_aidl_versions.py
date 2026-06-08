#!/usr/bin/env python3
"""
For each android.hardware.*-V*-ndk.so vendor prebuilt in the dump, look up
the corresponding aidl_interface block in the AOSP source tree
(hardware/interfaces/) and decide whether the prebuilt is safe to drop:

  DROP    — source builds this exact version (frozen or current).
            Vendor consumers will resolve to /system/lib*/<basename> via
            the AIDL -ndk linker namespace exemption.

  KEEP    — source does NOT build this version (orphan version that the
            vendor was clinging to). Dropping it would leave vendor
            consumers with an unresolvable DT_NEEDED at process load.

  UNKNOWN — script could not locate the source aidl_interface block at
            all. Defaults to KEEP for safety; flag for manual review.

Output: a table (family, version, source location, drop/keep, reason).

Usage:
  python3 device/nubia/NX809J/verify_aidl_versions.py
"""

import os
import re
import subprocess
import sys
from collections import defaultdict

ROOT = '/var/home/beast/android/lineage'
DUMP = os.path.join(ROOT, 'vendor/nubia/NX809J/proprietary')
INTERFACES = os.path.join(ROOT, 'hardware/interfaces')

# Match android.{hardware,system,media,frameworks}.* and vendor.qti.* AIDL prebuilts
BN_RE = re.compile(
    r'^(?:'
    r'(android)\.(hardware|system|media|frameworks)\.([a-zA-Z0-9_.]+)'
    r'|(vendor)\.(qti)\.([a-zA-Z0-9_.]+)'
    r')-V(\d+)-ndk\.so$'
)


# Roots to search for aidl_interface blocks. Keyed by (top-domain, subdomain).
# android.hardware.*  -> hardware/interfaces/
# android.system.*    -> system/hardware/interfaces/
# vendor.qti.*        -> vendor/qcom/opensource/{interfaces,commonsys-intf} +
#                       hardware/qcom-caf/{common,sm8750}
SEARCH_ROOTS = {
    ('android', 'hardware'): [os.path.join(ROOT, 'hardware/interfaces')],
    ('android', 'system'): [os.path.join(ROOT, 'system/hardware/interfaces')],
    ('android', 'media'): [
        os.path.join(ROOT, 'system/hardware/interfaces/media'),
        os.path.join(ROOT, 'frameworks/av/media'),
        os.path.join(ROOT, 'frameworks/base'),
    ],
    ('android', 'frameworks'): [
        os.path.join(ROOT, 'frameworks/hardware/interfaces'),
        os.path.join(ROOT, 'frameworks/native'),
        os.path.join(ROOT, 'frameworks/base'),
    ],
    ('vendor', 'qti'): [
        os.path.join(ROOT, 'vendor/qcom/opensource/interfaces'),
        os.path.join(ROOT, 'vendor/qcom/opensource/commonsys-intf'),
        os.path.join(ROOT, 'vendor/qcom/opensource'),
        os.path.join(ROOT, 'hardware/qcom-caf/common'),
        os.path.join(ROOT, 'hardware/qcom-caf/sm8750'),
    ],
}


def collect_basenames():
    out = []
    for root, dirs, fs in os.walk(DUMP):
        for f in fs:
            m = BN_RE.match(f)
            if not m:
                continue
            if m.group(1):  # android.{hardware,system}.*
                top, sub, fam = m.group(1), m.group(2), m.group(3)
            else:           # vendor.qti.*
                top, sub, fam = m.group(4), m.group(5), m.group(6)
            ver = int(m.group(7))
            out.append((f, top, sub, fam, ver))
    out.sort()
    return out


def find_interface_bp(top, sub, family):
    """Locate the Android.bp containing aidl_interface { name: "<top>.<sub>.<family>" ... }.

    Returns (path, block_text) or (None, None).
    """
    full_name = f'{top}.{sub}.{family}'
    roots = SEARCH_ROOTS.get((top, sub), [])
    for base in roots:
        # Try the obvious path first: <base>/<family-with-slashes>/aidl/Android.bp
        candidate = os.path.join(base, family.replace('.', '/'), 'aidl', 'Android.bp')
        if os.path.isfile(candidate):
            block = extract_block(candidate, full_name)
            if block:
                return candidate, block
        # Sub-aidl variant: progressively shorter dotted prefix
        parts = family.split('.')
        for cut in range(len(parts), 0, -1):
            candidate = os.path.join(base, '/'.join(parts[:cut]), 'aidl', 'Android.bp')
            if os.path.isfile(candidate):
                block = extract_block(candidate, full_name)
                if block:
                    return candidate, block
    # Fall back to grep across all search roots
    for base in roots:
        try:
            r = subprocess.run(
                ['grep', '-rl', '--include=Android.bp', f'name: "{full_name}"', base],
                capture_output=True, text=True, timeout=20,
            )
        except (subprocess.TimeoutExpired, FileNotFoundError):
            continue
        for path in r.stdout.splitlines():
            block = extract_block(path, full_name)
            if block:
                return path, block
    return None, None


def _extract_named_block(text, full_name, decl_filter):
    """Find a Soong-style block named <full_name> whose declaration line
    matches decl_filter (a substring like 'aidl_interface' or
    'aidl_interface_defaults'). Returns the block text or None.
    """
    needle = f'"{full_name}"'
    search_from = 0
    while True:
        idx = text.find(needle, search_from)
        if idx < 0:
            return None
        pre = text[:idx]
        open_brace = pre.rfind('{')
        if open_brace < 0:
            search_from = idx + len(needle)
            continue
        decl_start = pre.rfind('\n', 0, open_brace)
        decl_start = 0 if decl_start < 0 else decl_start + 1
        decl = pre[decl_start:open_brace].strip()
        if decl_filter not in decl:
            search_from = idx + len(needle)
            continue
        depth = 1
        j = open_brace + 1
        while j < len(text) and depth > 0:
            if text[j] == '{':
                depth += 1
            elif text[j] == '}':
                depth -= 1
            j += 1
        return text[open_brace:j]


def extract_block(bp_path, full_name):
    """Extract the aidl_interface { name: "<full_name>" ... } block.

    Resolves `defaults: [...]` references by finding any
    aidl_interface_defaults blocks of the same name in the same file
    and inlining their content (so vendor_available / stability /
    versions defined in a defaults block are visible to the parser).
    """
    try:
        text = open(bp_path).read()
    except OSError:
        return None
    block = _extract_named_block(text, full_name, 'aidl_interface')
    if block is None:
        return None
    # Find defaults references
    defaults_field = re.search(r'\bdefaults\s*:\s*\[(.*?)\]', block, re.S)
    if defaults_field:
        defaults_names = re.findall(r'"([^"]+)"', defaults_field.group(1))
        for d in defaults_names:
            db = _extract_named_block(text, d, 'aidl_interface_defaults')
            if db:
                # Append the defaults block content so the parser can see
                # inherited vendor_available / stability / etc.
                block = block + '\n' + db
    return block


def _match_bracket(text, start_idx, open_ch='[', close_ch=']'):
    """Given index of opening bracket, return index just past matching close."""
    assert text[start_idx] == open_ch
    depth = 1
    i = start_idx + 1
    while i < len(text) and depth > 0:
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
        i += 1
    return i if depth == 0 else -1


def _extract_field(block, field_name):
    """Extract the [...] value of `field_name: [...]` accounting for nesting."""
    m = re.search(r'\b' + re.escape(field_name) + r'\s*:\s*\[', block)
    if not m:
        return None
    open_idx = m.end() - 1
    close_idx = _match_bracket(block, open_idx, '[', ']')
    if close_idx < 0:
        return None
    return block[open_idx + 1:close_idx - 1]


def parse_versions(block):
    """Parse versions and current/unfrozen state from an aidl_interface block.

    Returns (versions_list_int, current_version_int_or_None, frozen_bool, raw_versions_field).
    """
    versions = []
    raw = _extract_field(block, 'versions_with_info')
    if raw is not None:
        # Modern format: list of {version: "N", imports: [...]} objects.
        # We need version values that are immediate children of the
        # versions_with_info brackets, not nested inside imports brackets.
        # Walk the raw text and collect 'version: "N"' occurrences at the
        # top level of curly-brace nesting.
        depth_curly = 0
        i = 0
        while i < len(raw):
            ch = raw[i]
            if ch == '{':
                depth_curly += 1
            elif ch == '}':
                depth_curly -= 1
            # Match 'version: "N"' only at depth 1 (inside an object literal)
            if depth_curly == 1 and raw[i:i+8] == 'version:':
                m = re.match(r'version:\s*"(\d+)"', raw[i:])
                if m:
                    versions.append(int(m.group(1)))
                    i += m.end()
                    continue
            i += 1
    else:
        raw = _extract_field(block, 'versions')
        if raw is not None:
            for m in re.finditer(r'"(\d+)"', raw):
                versions.append(int(m.group(1)))
    # frozen / unstable
    frozen_m = re.search(r'\bfrozen\s*:\s*(true|false)', block)
    frozen = frozen_m.group(1) == 'true' if frozen_m else None
    unstable_m = re.search(r'\bunstable\s*:\s*(true|false)', block)
    unstable = unstable_m.group(1) == 'true' if unstable_m else False
    # vendor_available — is the source variant consumable by vendor processes?
    vendor_available_m = re.search(r'\bvendor_available\s*:\s*(true|false)', block)
    vendor_available = vendor_available_m.group(1) == 'true' if vendor_available_m else None
    # vintf-stable interfaces are inherently vendor-consumable by design
    stability_m = re.search(r'\bstability\s*:\s*"vintf"', block)
    is_vintf = stability_m is not None
    versions = sorted(set(versions))
    current = None
    if not unstable and versions:
        if frozen:
            current = None
        else:
            current = max(versions) + 1
    return versions, current, frozen, vendor_available, is_vintf, (raw or '').strip()[:80]


def main():
    bns = collect_basenames()
    print(f'Found {len(bns)} AIDL -V*-ndk prebuilts in dump (android.* + vendor.qti.*)')
    print()
    rows = []
    drop_count = keep_count = unknown_count = 0
    for bn, top, sub, family, ver in bns:
        bp, block = find_interface_bp(top, sub, family)
        full_id = f'{top}.{sub}.{family}'
        if not bp or not block:
            action = 'UNKNOWN'
            reason = f'aidl_interface block not located ({full_id})'
            unknown_count += 1
            rows.append((bn, full_id, ver, '', '', '', '', action, reason))
            continue
        versions, current, frozen, vendor_avail, is_vintf, raw = parse_versions(block)
        built = set(versions)
        if current is not None:
            built.add(current)
        version_match = ver in built
        # Sanction the drop only if the version matches AND the source is
        # vendor-consumable (vendor_available: true OR stability:"vintf").
        # vintf interfaces are inherently vendor-consumable by AIDL contract.
        consumable = (vendor_avail is True) or is_vintf
        if version_match and consumable:
            action = 'DROP'
            drop_count += 1
        elif version_match and not consumable:
            action = 'KEEP'  # source has the version but not vendor-consumable; safe to keep
            keep_count += 1
        else:
            action = 'KEEP'
            keep_count += 1
        bp_rel = os.path.relpath(bp, ROOT)
        reason = f'frozen={versions} current={current} vendor_available={vendor_avail} vintf={is_vintf}'
        rows.append((bn, full_id, ver, bp_rel, str(versions), str(current), str(frozen), action, reason))
    # Write detailed table
    out_path = os.path.join(ROOT, 'device/nubia/NX809J/.aidl_verify.txt')
    with open(out_path, 'w') as f:
        f.write('# AIDL prebuilt verification — DROP / KEEP / UNKNOWN per basename.\n')
        f.write(f'# Dump candidates: {len(bns)} (android.hardware.* + android.system.*)\n')
        f.write(f'# DROP: {drop_count}  KEEP: {keep_count}  UNKNOWN: {unknown_count}\n')
        f.write('\n')
        f.write('basename | domain.family | want_version | source_path | versions | current | frozen | action | reason\n')
        f.write('---\n')
        for r in rows:
            f.write(' | '.join(str(x) for x in r) + '\n')
    print(f'DROP: {drop_count}')
    print(f'KEEP: {keep_count}')
    print(f'UNKNOWN: {unknown_count}')
    print()
    print(f'Detail: {out_path}')
    print()
    print('=== KEEP entries (orphan versions / source has dropped support) ===')
    for r in rows:
        if r[7] == 'KEEP':
            print(f'  {r[0]:60s}  src={r[3]}  versions={r[4]}  current={r[5]}')
    print()
    print('=== UNKNOWN entries (source Android.bp not located) ===')
    for r in rows:
        if r[7] == 'UNKNOWN':
            print(f'  {r[0]:60s}  family={r[1]}')


if __name__ == '__main__':
    main()

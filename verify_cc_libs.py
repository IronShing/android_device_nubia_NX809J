#!/usr/bin/env python3
"""
Bulk-verifier for non-AIDL cc_library / cc_library_shared modules in the
vendor dump. Mirror of verify_aidl_versions.py but for plain shared libs.

For each .so file under vendor/nubia/NX809J/proprietary/vendor/lib*/
(NOT inside vendor/lib*/hw/, NOT inside an APK lib dir, NOT matching a
HAL-impl basename pattern), check if AOSP source has a cc_library or
cc_library_shared module of the same name with vendor_available: true
(or inheriting it via cc_defaults).

Output: DROP / KEEP / UNKNOWN table at .cc_lib_verify.txt.

DROP    — source defines this module with vendor_available; safe to drop.
KEEP    — basename has no source counterpart, OR source exists but is
          not vendor_available; prebuilt is canonical.
UNKNOWN — there's a source module by that name but the vendor_available
          state is unclear (e.g. set via a deeper transitive default the
          parser doesn't follow). Defaults to KEEP for safety.
"""

import os
import re
import subprocess
import sys
from collections import defaultdict

ROOT = '/var/home/beast/android/lineage'
DUMP = os.path.join(ROOT, 'vendor/nubia/NX809J/proprietary')

# Roots to search for cc_library source modules. Prioritized order.
SEARCH_ROOTS = [
    'frameworks',
    'system',
    'hardware/interfaces',
    'hardware/libhardware',
    'hardware/libhardware_legacy',
    'hardware/qcom-caf/common',
    'external',
    'vendor/qcom/opensource',
]

# Skip basenames matching HAL-impl patterns or chip-specific drivers.
HAL_IMPL_PATTERNS = [
    re.compile(r'.*-impl(-.*)?\.so$'),
    re.compile(r'.*-service(-.*)?\.so$'),
    re.compile(r'.*-V\d+-service\.so$'),
    re.compile(r'^audio\..*\.so$'),
    re.compile(r'^sensors\..*\.so$'),
    re.compile(r'^gralloc\..*\.so$'),
    re.compile(r'^lights\..*\.so$'),
    re.compile(r'^gps\..*\.so$'),
    re.compile(r'^camera\..*\.so$'),
    re.compile(r'^nfc_nci\..*\.so$'),
    re.compile(r'^bluetooth\..*\.so$'),
    re.compile(r'^keystore\..*\.so$'),
]


def is_hal_impl(basename):
    return any(p.match(basename) for p in HAL_IMPL_PATTERNS)


def collect_dump_libs():
    """Walk the dump for .so files in candidate locations."""
    libs = []
    for root, dirs, fs in os.walk(DUMP):
        # Skip APK install dirs (handled elsewhere)
        rel = os.path.relpath(root, DUMP)
        parts = rel.split('/')
        if len(parts) >= 4 and parts[1] in ('app', 'priv-app'):
            continue
        # Skip vendor/lib*/hw/ — those are HAL impls, never DROP candidates
        if '/hw' in '/' + rel + '/' and ('/lib/' in '/' + rel + '/' or '/lib64/' in '/' + rel + '/'):
            continue
        # Skip vendor/lib*/<subdir>/ where subdir is a known HAL subdir
        # (we want top-level vendor/lib64/foo.so only for cleanest match)
        for f in fs:
            if not f.endswith('.so'):
                continue
            full = os.path.join(root, f)
            if os.path.islink(full):
                continue
            # Skip versioned .so.N
            if re.match(r'.+\.so\.\d+$', f):
                continue
            # Skip HAL-impl basename patterns
            if is_hal_impl(f):
                continue
            libs.append((f, os.path.relpath(full, DUMP)))
    libs.sort()
    return libs


_NAME_INDEX = None  # name -> list of Android.bp paths


def build_name_index():
    """One-shot scan: grep every Android.bp under SEARCH_ROOTS for
    `name: "..."` lines and build a basename->path index. ~hundreds of
    thousands of bp files; one grep call per root.
    """
    global _NAME_INDEX
    if _NAME_INDEX is not None:
        return _NAME_INDEX
    idx = defaultdict(list)
    for sr in SEARCH_ROOTS:
        base = os.path.join(ROOT, sr)
        if not os.path.isdir(base):
            continue
        try:
            r = subprocess.run(
                ['grep', '-rEHn', '--include=Android.bp', r'name:\s*"[a-zA-Z0-9_.@+-]+"', base],
                capture_output=True, text=True, timeout=300,
            )
        except (subprocess.TimeoutExpired, FileNotFoundError):
            continue
        for line in r.stdout.splitlines():
            # path:lineno:    name: "foo",
            try:
                path, _lineno, rest = line.split(':', 2)
            except ValueError:
                continue
            m = re.search(r'name:\s*"([^"]+)"', rest)
            if m:
                idx[m.group(1)].append(path)
    _NAME_INDEX = idx
    return idx


def find_cc_library(modname):
    """Locate the source Android.bp for a cc_library / cc_library_shared
    with the given name. Return (path, block_text, vendor_available_bool).
    """
    idx = build_name_index()
    candidates = idx.get(modname, [])
    for path in candidates:
        block, va = extract_cc_block(path, modname)
        if block:
            return path, block, va
    return None, None, None


def _match_block(text, name_pattern, decl_keywords):
    """Find a Soong block matching name_pattern (regex) and whose declaration
    contains any keyword in decl_keywords."""
    for m in re.finditer(name_pattern, text):
        idx = m.start()
        pre = text[:idx]
        ob = pre.rfind('{')
        if ob < 0:
            continue
        decl_start = pre.rfind('\n', 0, ob)
        decl_start = 0 if decl_start < 0 else decl_start + 1
        decl = pre[decl_start:ob].strip()
        if not any(k in decl for k in decl_keywords):
            continue
        depth = 1
        j = ob + 1
        while j < len(text) and depth > 0:
            if text[j] == '{':
                depth += 1
            elif text[j] == '}':
                depth -= 1
            j += 1
        return text[ob:j]
    return None


def extract_cc_block(bp_path, modname):
    try:
        text = open(bp_path).read()
    except OSError:
        return None, None
    name_re = re.compile(r'name:\s*"' + re.escape(modname) + r'"')
    block = _match_block(text, name_re,
                         ['cc_library', 'cc_library_shared',
                          'cc_library_libpower'])
    if not block:
        return None, None
    # Direct vendor_available
    va_m = re.search(r'\bvendor_available\s*:\s*(true|false)', block)
    va = va_m.group(1) == 'true' if va_m else None
    if va is True:
        return block, True
    # Resolve defaults references
    defaults_field = re.search(r'\bdefaults\s*:\s*\[(.*?)\]', block, re.S)
    if defaults_field:
        defaults_names = re.findall(r'"([^"]+)"', defaults_field.group(1))
        for d in defaults_names:
            d_re = re.compile(r'name:\s*"' + re.escape(d) + r'"')
            d_block = _match_block(text, d_re, ['cc_defaults'])
            if d_block:
                d_va = re.search(r'\bvendor_available\s*:\s*(true|false)', d_block)
                if d_va and d_va.group(1) == 'true':
                    return block, True
    return block, False


def main():
    libs = collect_dump_libs()
    print(f'Candidate dump libs: {len(libs)}')
    print()
    rows = []
    drop = keep = unknown = 0
    for bn, rel in libs:
        modname = bn[:-3]  # strip .so
        bp, block, va = find_cc_library(modname)
        if bp is None:
            action = 'KEEP'
            reason = 'no source cc_library/cc_library_shared with this name'
            keep += 1
            rows.append((bn, rel, '', '', action, reason))
            continue
        if va is True:
            action = 'DROP'
            reason = 'source vendor_available'
            drop += 1
        else:
            action = 'KEEP'
            reason = 'source exists but not vendor_available'
            keep += 1
        bp_rel = os.path.relpath(bp, ROOT)
        rows.append((bn, rel, bp_rel, str(va), action, reason))
    out = os.path.join(ROOT, 'device/nubia/NX809J/.cc_lib_verify.txt')
    with open(out, 'w') as f:
        f.write('# cc_library / cc_library_shared bulk verifier\n')
        f.write(f'# Candidates: {len(libs)}  DROP: {drop}  KEEP: {keep}\n\n')
        f.write('basename | dump_path | source_path | vendor_available | action | reason\n')
        f.write('---\n')
        for r in rows:
            f.write(' | '.join(r) + '\n')
    print(f'DROP: {drop}')
    print(f'KEEP: {keep}')
    print(f'Detail: {out}')


if __name__ == '__main__':
    main()

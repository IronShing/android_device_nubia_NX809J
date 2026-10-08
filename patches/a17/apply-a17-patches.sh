#!/bin/bash
# EvolutionX 12.3 (cnb / Android 17) tree: re-apply the few NX809J fixes that live in
# shared repos we did NOT fork (everything else is a fork pinned by manifest/nx809j.xml).
# Run after every `repo sync`. Idempotent: already-applied patches are skipped.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(cd "$HERE/../../../../.." && pwd)"   # source root

apply() {  # $1 = repo path (rel to TOP)   $2 = patch subdir
  local repo="$TOP/$1" pd="$HERE/$2"
  echo "== $1 =="
  [ -d "$repo/.git" ] || { echo "  !! $1 not found"; return; }
  for p in "$pd"/*.patch; do
    [ -e "$p" ] || continue
    local subj; subj="$(sed -n 's/^Subject: \[PATCH[^]]*\] //p' "$p" | head -1)"
    if git -C "$repo" log --oneline -50 --format='%s' | grep -qxF "$subj"; then
      echo "  ok  (already applied) $(basename "$p")"
    elif git -C "$repo" am --keep-cr --3way "$p" >/dev/null 2>&1; then
      echo "  +   applied $(basename "$p")"
    else
      git -C "$repo" am --abort >/dev/null 2>&1
      echo "  !!  FAILED $(basename "$p") — apply by hand"
    fi
  done
}

# build/make signing fix: upstream in EvolutionX 12.3 (2fa923193d), no longer patched here.
apply device/qcom/sepolicy              device_qcom_sepolicy              # gppservice -> gmscore_app/system_app
apply device/qcom/sepolicy_vndr/sm8750  device_qcom_sepolicy_vndr_sm8750  # canoe genfs_contexts slash
apply hardware/qcom-caf/common          hardware_qcom-caf_common          # canoe in UM_6_6_FAMILY
apply packages/apps/Glimpse             packages_apps_Glimpse             # shared-URI metadata crash
apply packages/apps/SecureElement       packages_apps_SecureElement       # absent eSE terminal never blocks main thread
apply packages/modules/Nfc              packages_modules_Nfc              # Type-F on the eSE F route (FeliCa)
echo "done."

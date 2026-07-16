#!/bin/bash
# Re-apply NX809J out-of-tree fixes after a `repo sync` (especially `-d` /
# `--force-sync`) resets the shared LOS repos these touch. We don't fork those
# repos, so the fixes live here as patches (this dir is in the forked device tree,
# so it always survives). Idempotent: already-applied patches are skipped.
#
# Mirrors: frameworks/base @ branch nx809j, vendor/lineage @ branch nx809j,
#          packages/apps/CarrierConfig @ branch nx809j-volte-41902.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(cd "$HERE/../../../.." && pwd)"   # lineage source root

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

apply frameworks/base              frameworks_base
apply vendor/lineage               vendor_lineage
apply packages/apps/CarrierConfig  packages_apps_CarrierConfig

# Display: the local_manifest pulls the OnePlus-SM8850 display source (sm8850,
# composer3-V4) to the sm8750/display path; these carry the NX809J port fixes
# (SetupAtomic bounds fix, header wiring, libvmmem, namespace). See sm8850 README.
apply hardware/qcom-caf/sm8750/display/core  display_sm8850/core
apply hardware/qcom-caf/sm8750/display/hal   display_sm8850/hal
echo "done."

#!/bin/bash
# NX809J coherent SOURCE display stack — re-apply after a repo sync regenerates the
# (gitignored) vendor/nubia/NX809J/Android.bp. Validated 2026-07-06 (build v15):
# source composer(@3)/allocator/demura, 0 unresolved vendor deps, composer loads + init arms it.
#
# Two parts:
#  1. Flip prefer:true -> prefer:false on the SDM-CORE prebuilts so the SOURCE libs win
#     (source libsdmutils exports sdm::IsExtendedRange, which libsdmclient needs; the blobs
#     don't). vendor/nubia is FIRST in PRODUCT_SOONG_NAMESPACES so prefer must be flipped,
#     not just relied on. Do NOT flip the HAL-support libs (libqdutils/libqservice/
#     libgralloc.qti/libgpu_tonemapper/libhistogram/libdisplayconfig.qti) — their source
#     needs headers (QServiceUtils.h) not wired for a standalone source build; keep as blob.
#  2. The composer .rc + mapper.qti.xml prebuilt_etc blocks live in Android.bp too — see
#     display-prebuilt-composer-rc.bp.txt (mapper block kept; composer prebuilt block REMOVED
#     since composer is now source). Plus composer_version=v3_3 in vendor_hals.mk makes the
#     source composer concrete (implements getDisplayConfigurations/notifyExpectedPresent).
# Also requires the display-source-build/ patches (formats.cpp IsExtendedRange port etc.)
# applied into the non-git hardware/qcom-caf/sm8750/display tree.
set -e
BP="${1:-vendor/nubia/NX809J/Android.bp}"
python3 - "$BP" <<'PY'
import re,sys
f=sys.argv[1]
CORE={"libsdmutils","libsdmcore","libsdmdal","libsdedrm","libdrmutils","libdisplaydebug","libsdmextension"}
res=[];cur=[];flipped=[]
def flush(buf):
    t="\n".join(buf); m=re.search(r'name:\s*"([^"]+)"',t); nm=m.group(1) if m else None
    if nm in CORE and re.search(r'^\s*prefer:\s*true,\s*$',t,re.M):
        t=re.sub(r'(^\s*)prefer:\s*true,(\s*)$',r'\1prefer: false,\2',t,flags=re.M);flipped.append(nm)
    return t
for ln in open(f).read().split("\n"):
    cur.append(ln)
    if ln.strip()=="}": res.append(flush(cur));cur=[]
if cur:res.append("\n".join(cur))
open(f,"w").write("\n".join(res))
print("SDM-core prefer:false flipped:",sorted(set(flipped)))
PY
echo "Done. Now rebuild: m vendorimage"

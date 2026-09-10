#!/usr/bin/env python3
"""
NX809J proprietary-files.txt regenerator + DT_NEEDED sanity-checker.

Strategy (per user direction, 2026-04-07):
  - Include EVERY regular file under vendor/nubia/NX809J/proprietary/.
    Physical presence in the dump is the only inclusion gate.
  - DT_NEEDED walk is *diagnostics only*: warn on unresolved deps so we
    can spot dump-completeness issues. Never use it to drop anything.
  - Preserve any leading '-' (auto-symlink) and ';' (force overwrite)
    markers from the existing proprietary-files.txt.
  - Group output by subdirectory with '# Section' headers.
  - Idempotent: rerunning yields the same file.

Outputs:
  - device/nubia/NX809J/proprietary-files.txt (rewritten)
  - device/nubia/NX809J/.closure_unresolved.txt (warnings, parent -> NEEDED)
  - device/nubia/NX809J/.closure_baseline_diff.txt (in baseline, missing from dump)
  - prints summary to stdout

Run from anywhere; paths are anchored to this script's directory.
"""

import os
import re
import subprocess
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DUMP_ROOT = os.path.join(HERE, '..', '..', '..', 'vendor', 'nubia', 'NX809J', 'proprietary')
DUMP_ROOT = os.path.normpath(DUMP_ROOT)
PROP_TXT = os.path.join(HERE, 'proprietary-files.txt')
EXISTING_TXT = PROP_TXT  # read prefixes from existing file before overwriting
BASELINE_TXT = os.path.join(HERE, '.bringup_orig_blobs.txt')
UNRESOLVED_OUT = os.path.join(HERE, '.closure_unresolved.txt')
BASELINE_DIFF_OUT = os.path.join(HERE, '.closure_baseline_diff.txt')

# DT_NEEDED entries that are part of the AOSP system image (vendor variant
# always linkable). We don't warn on these. Conservative list — add as needed.
SYSTEM_LIBS = {
    # bionic
    'libc.so', 'libdl.so', 'libm.so', 'libpthread.so', 'librt.so',
    'ld-android.so', 'libstdc++.so',
    # libcxx
    'libc++.so', 'libc++_shared.so',
    # core utility/log
    'liblog.so', 'libcutils.so', 'libutils.so', 'libbase.so',
    'libbinder.so', 'libbinder_ndk.so', 'libhidlbase.so', 'libhidlmemory.so',
    'libhwbinder.so', 'libfmq.so', 'libvndksupport.so',
    'libpropertyinfoparser.so', 'libpropertyinfoserializer.so',
    # selinux/crypto
    'libselinux.so', 'libcrypto.so', 'libssl.so', 'libcrypto_utils.so',
    # net
    'libnetd_client.so', 'libnetutils.so', 'libnl.so', 'libpcap.so',
    # graphics / mm common
    'libEGL.so', 'libGLESv1_CM.so', 'libGLESv2.so', 'libGLESv3.so',
    'libgui.so', 'libui.so', 'libsync.so', 'libnativewindow.so',
    'libsurfaceflinger.so',
    # audio common
    'libaudioutils.so',
    # data structures
    'libxml2.so', 'libz.so', 'libzip.so', 'libziparchive.so',
    'libjsoncpp.so', 'libprotobuf-cpp-lite.so', 'libprotobuf-cpp-full.so',
    # backtrace/unwind
    'libbacktrace.so', 'libunwind.so', 'libunwindstack.so',
    # process/sys
    'libprocessgroup.so', 'libcgrouprc.so', 'libcgrouprc_format.so',
    'libdmabufheap.so', 'libion.so',
    # camera/media frameworks (these have vendor variants in AOSP)
    'libcamera_metadata.so',  # exists in frameworks/av/camera
    'libcamera_client.so',
    'libmediandk.so', 'libmedia.so',
    # wifi/sysprop
    'libsysutils.so',
    # apex / linkerconfig
    'libapexd.so',
    # neural networks
    'libneuralnetworks.so',
    # added 2026-04-07 after closure run #1: confirmed AOSP-side libs
    # that the linker resolves from /system/lib*/ but weren't in the
    # initial allowlist. Each was an "unresolved NEEDED" warning that
    # turned out to be a system library.
    'libandroid_net.so',
    'libft2.so',
    'libapexsupport.so',         # NEEDED by libbinder.so itself in AOSP
    'libvendorsupport.so',
    'libcom.android.tethering.connectivity_native.so',
}

# libgcc.so is genuinely a Hexagon DSP-side artifact (FastRPC-loaded skel
# pairs run on aDSP/cDSP, not on the ARM cores). Modern AOSP never
# produces an ARM-side libgcc.so, so it's safe to suppress — but only
# when the parent ELF lives under a DSP path. An unexpected ARM-side
# libgcc.so dep should still warn so we catch dump-pollution issues.
DSP_PATH_SUBSTRINGS = (
    '/dsp/', '/adsp/', '/cdsp/', '/rfsa/',
)
def is_dsp_parent(rel_path):
    return any(s in '/' + rel_path for s in DSP_PATH_SUBSTRINGS) or \
           rel_path.endswith('_skel.so') or rel_path.endswith('Skel.so')

# File extensions we treat as ELF candidates for readelf walk
ELF_SUFFIXES = ('.so',)
# Binaries (no .so suffix) — also ELF, also walked
def is_likely_elf(path):
    if path.endswith('.so'):
        return True
    rel = path[len(DUMP_ROOT)+1:] if path.startswith(DUMP_ROOT) else path
    # vendor/bin/ and vendor/bin/hw/ executables, no extension
    if '/bin/' in rel and '.' not in os.path.basename(rel):
        return True
    return False


PARTITION_TOPLEVELS = ('vendor', 'system', 'system_ext', 'product', 'odm', 'recovery')

# Subdirectories under a partition (e.g. vendor/overlay/) that extract-utils
# has no package handler for. Files under these are skipped from
# proprietary-files.txt — they would just trigger AssertionError otherwise.
# 'overlay' = RRO overlay APKs; OEM RROs from stock Android rarely apply
# correctly to LineageOS (different framework-res, different package paths)
# and have no extract-utils writer.
UNHANDLED_SUBDIRS = (
    'overlay',
)

# Per-basename exclusions. Each entry must be justified — this is NOT a
# license to broad-strokes-prune. Only add things you can prove are
# system-side from AOSP source on this product, where the vendor prebuilt
# would conflict with the source-built module via partition mismatch.
# Runtime-mounted partition path prefixes — files under these come from
# SEPARATE physical partitions on the device, mounted at runtime per
# fstab.qcom. They're NOT part of the vendor partition image and we
# do NOT install them at build time. The stock device's modem/dsp/
# bluetooth/soccp partitions stay flashed and mount at the listed paths.
#
# Per device/nubia/NX809J/rootdir/etc/fstab.qcom:
#   /dev/block/bootdevice/by-name/modem      /vendor/firmware_mnt    vfat
#   /dev/block/bootdevice/by-name/dsp        /vendor/dsp             ext4
#   /dev/block/bootdevice/by-name/bluetooth  /vendor/bt_firmware     vfat
#   /dev/block/bootdevice/by-name/soccp      /vendor/soccp_firmware  vfat
#
# Files under any prefix in this set are completely dropped by closure.py
# (no proprietary-files.txt entry, no Soong module, no PRODUCT_COPY_FILES).
# The dump has them only because the extract tool walked the live mounts.
RUNTIME_PARTITION_PATHS = {
    'vendor/firmware_mnt/',  # modem partition — modem firmware + mcfg
    'vendor/dsp/',           # dsp partition — adsp/cdsp firmware
    'vendor/bt_firmware/',   # bluetooth partition — BT chip firmware
    'vendor/soccp_firmware/',# soccp partition — SoC co-processor firmware
    'vendor/odm_dlkm/',      # odm_dlkm partition (mounted elsewhere)
}

# Path prefixes whose entire subtree is migrated to PRODUCT_COPY_FILES
# (Make-level install), bypassing extract-utils + Soong entirely.
# Used for files that:
#   1. Live in the actual vendor partition (NOT a separate runtime mount).
#   2. extract-utils silently drops or mishandles them (no Soong module).
#   3. Are opaque blobs that don't need Soong's module shape.
# closure.py emits a generated firmware_pcf.mk with one PRODUCT_COPY_FILES
# entry per file under any PCF_BYPASS prefix. device.mk includes that
# generated snippet. Idempotent.
PCF_BYPASS_PATHS = {
    # Audio calibration data: 78 files, depth 10. Lives in the actual
    # vendor partition. extract-utils silently drops these (0 bp refs).
    # Audio platform calibration tables for Qualcomm audio HAL.
    'vendor/etc/acdbdata/',
    # Qualcomm profiler libs: 21 files, depth 9. Lives in vendor
    # partition. 0 bp refs from extract-utils (treats vendor/qprof as
    # generic file copy). vendor/bin/qprof itself is already in
    # EXCLUDE_PATHS — this covers the libs.
    'vendor/qprof/',
}

# Files to install via cc_prebuilt with DISABLE_DEPS — extract-utils
# emits the prebuilt module but skips shared_libs auto-generation. Use
# for vendor blobs whose DT_NEEDED graph hits Soong's multi-version
# constraint or other dep-graph problems but the .so itself is needed.
# At runtime, the dynamic linker still uses the .so's actual DT_NEEDED
# entries (which we leave intact) — DISABLE_DEPS only affects Soong's
# build-time view.
DISABLE_DEPS_PATHS = {
    # camx.device-impl + camx.provider-impl: Qualcomm camera HAL impls.
    # Built against an older AIDL graphics.common-V5 + graphics.allocator-V2
    # combo. AOSP source's allocator-V2 imports common-V7, so the
    # transitive closure mixes V5 (direct from camx) and V7 (via
    # allocator-V2 → common-V7) → multi-version conflict. Cannot drop
    # (camera HAL would lose its impl), cannot patchelf without breaking
    # runtime symbols. DISABLE_DEPS skips Soong's dep walk — extract-utils
    # emits the cc_prebuilt module with no shared_libs, the source-side
    # camx.{provider,device}-impl reference resolves to the prebuilt
    # name, and runtime DT_NEEDED loads V5/V7 .so files independently.
    'vendor/lib64/camx.device-impl.so',
    'vendor/lib64/camx.provider-impl.so',
    # libqcodec2_core: codec2 framework core. Multi-version
    # graphics.common V5+V7 from old AIDL build. Same DISABLE_DEPS
    # pattern as camx.{device,provider}-impl.
    'vendor/lib64/libqcodec2_core.so',
    # vendor.qti.hardware.camera.offlinecamera-service-impl: offline
    # camera HAL impl helper. Multi-version graphics.allocator V1+V2.
    # Same DISABLE_DEPS pattern.
    'vendor/lib64/vendor.qti.hardware.camera.offlinecamera-service-impl.so',
    # === iter 192: cross-DSO CFI variant mismatch for libwifi-hal-qcom ===
    # hal_proxy_daemon and sigma_dut are non-CFI vendor binaries (extracted
    # from stock Nubia dump) that DT_NEEDED libwifi-hal-qcom.so. After
    # iter 190 dropped our libwifi-hal-qcom prebuilt, the source-built
    # version from hardware/qcom-caf/wlan/qcwcn produces only the
    # android_vendor_arm64_armv9-a_shared_cfi variant (CFI-instrumented).
    # Soong's check_elf_file refuses to link a non-CFI binary against a CFI
    # variant of the same module name → silent dep drop → DT_NEEDED missing
    # error.
    #
    # Architectural reality: cross-DSO CFI is documented Android behavior.
    # Non-CFI binaries CAN dynamically link against CFI libraries at runtime
    # because the caller doesn't provide CFI metadata at the call site, so
    # the check isn't enforced. Soong's check_elf_file is being overly
    # strict about something runtime correctly handles.
    #
    # Install state verified iter 192 Step 1: source-built CFI variant of
    # libwifi-hal-qcom.so (606K, valid AArch64 ELF for Android 36) installs
    # to out/target/product/NX809J/vendor/lib64/libwifi-hal-qcom.so. Runtime
    # dlopen will resolve normally; build-time check_elf_file is the only
    # blocker.
    #
    # cnss_diag (the third wifi vendor binary) consumes libwifi-hal-ctrl
    # not libwifi-hal-qcom, and libwifi-hal-ctrl is cc_library_shared with
    # no CFI variant — its check_elf_file already PASSED at iter 191 after
    # the namespace import fix. Only the two libwifi-hal-qcom consumers
    # need DISABLE_DEPS.
    #
    # This is the architecturally correct resolution of the iter-187 variant
    # mismatch, which the iter 188-191 namespace cascade work didn't address
    # (the cascade was adjacent to the original problem, not the original
    # problem itself). Three-way convergence on Option 2 (agent + ChatGPT +
    # Gemini) with Gemini's cross-DSO CFI insight as the architectural
    # justification.
    'vendor/bin/hal_proxy_daemon',
    'vendor/bin/sigma_dut',
}

# === MANUAL_MODULE_RENAMES ===
# Maps vendor blob paths to a custom Soong/Make module name. The module
# emitted by extract-utils gets `name: "<rename>"` while the install path,
# filename, SONAME, and contents stay identical.
#
# Use case: vendor prebuilt has the same basename as a source-side cc_library
# in a different partition variant. Soong's overrides: directive doesn't
# fully suppress the source-side MODULE.TARGET registration at the
# Soong→Make conversion layer (verified iter 197 — overrides only affects
# install rules, not Make module registration). Renaming our prebuilt to a
# distinct Soong module name eliminates the kati duplicate-target error
# entirely without affecting runtime DT_NEEDED resolution (consumers look
# up the SONAME / install filename, not the Soong module name).
#
# This dict is merged into the auto-computed renames from
# compute_module_renames() at emit time.
MANUAL_MODULE_RENAMES = {
    # iter 198: libtensorflowlite_jni.so
    # 3 vendor consumers (libai_tflite, libimage_segment, libmfnr_network)
    # DT_NEEDED libtensorflowlite_jni.so for versioned @VERS_1.0 TfLite
    # symbols that source-built version doesn't export. Iter 194 restored
    # the prebuilt; iter 195 hit kati duplicate-target on
    # MODULE.TARGET.SHARED_LIBRARIES.libtensorflowlite_jni; iter 197
    # tried Soong overrides: but it doesn't suppress Make module
    # registration. This rename gives our prebuilt a distinct Soong
    # module name (libtensorflowlite_jni_nubia_vendor) so source and
    # prebuilt coexist without collision. Install filename remains
    # libtensorflowlite_jni.so (extract-utils preserves it via stem:),
    # so runtime DT_NEEDED lookup still resolves.
    'vendor/lib64/libtensorflowlite_jni.so': 'libtensorflowlite_jni_nubia_vendor',
}

# === FIX_SONAME_PATHS ===
# Maps vendor blob paths to a renamed install destination. extract-utils' parser
# understands the ;FIX_SONAME marker on `src:dst` proprietary-files.txt entries
# and patches the binary's SONAME via patchelf at extraction time, installing
# the renamed file at the dst path.
#
# Use case: vendor prebuilt has a same-SONAME source counterpart that produces
# a vendor variant (so prefer:true and overrides: don't cleanly resolve), AND
# we need to keep both files installed (e.g., consumer chains need DT_NEEDED
# rewriting via blob_fixup.replace_needed at extract-files.py to point at the
# renamed file).
#
# Mirrors dodge sm8750-common's pattern for libtensorflowlite_c.so handling.
FIX_SONAME_PATHS = {
    # iter 204: libtensorflowlite_c.so → libtensorflowlite_c_vendor.so
    # Nubia's libtensorflowlite_c.so defines TfLiteXNNPackDelegateCreate/Delete/
    # OptionsDefault@@VERS_1.0 — XNNPACK delegate symbols that source-built
    # libtensorflowlite_c.so doesn't export. The libVoiceSdk consumer cluster
    # (libVoiceSdk + libcapiv2uvvendor + liblistensoundmodel2vendor) needs
    # these symbols. FIX_SONAME renames Nubia's version to coexist with the
    # source-built version. Consumer DT_NEEDED entries are patched in
    # extract-files.py via blob_fixup.replace_needed.
    'vendor/lib64/libtensorflowlite_c.so': 'vendor/lib64/libtensorflowlite_c_vendor.so',
}

# === OVERRIDES_PATHS ===
# Maps vendor blob paths to the source-side module names they override.
# When emitted to proprietary-files.txt, the entry gets the ;OVERRIDES=<name>
# marker which extract-utils translates to overrides: ["<name>"] on the
# generated cc_prebuilt_library_shared module.
#
# Soong's overrides: directive suppresses the overridden module's MODULE.TARGET
# registration during Soong→Make conversion, preventing the kati duplicate-
# target error that would otherwise occur when both source-built and prebuilt
# libraries register the same Make module name.
#
# Use case: vendor prebuilts that must replace a same-named source module to
# provide consumer-required ABI (e.g., versioned symbols) that source doesn't
# export.
OVERRIDES_PATHS = {
    # iter 196: libtensorflowlite_jni.so
    # Restored from speculative iter-173 drop at iter 194. 3 vendor consumers
    # (libai_tflite, libimage_segment, libmfnr_network) DT_NEEDED this and
    # require versioned @VERS_1.0 TfLite symbols that source-built version
    # doesn't export. Source builds only system variant; restoration is clean
    # at the Soong layer but kati saw a duplicate MODULE.TARGET registration
    # at iter 195. OVERRIDES suppresses the source-side registration, leaving
    # our prebuilt as the sole installer.
    'vendor/lib64/libtensorflowlite_jni.so': 'libtensorflowlite_jni',
}

# Per-PATH exclusions where basename matching is ambiguous (e.g. generic
# binary names). Format: full relative path from proprietary/.
EXCLUDE_PATHS = {
    # QSPM (Qualcomm System Performance Manager) HAL: sets per-app perf hints for
    # ActivityTrigger/libqti-at (system side, absent on this ROM -- NO-GO 2026-09-07,
    # vendor tables are a CN app list). Always-on class hal, 7 MB RSS. The two CLIENT libs
    # (vendor.qti.qspmhal-V1-ndk, libqspm-mem-utils-vendor) are KEPT: libadreno_app_profiles.so
    # (dlopen'd by the Adreno GL/Vulkan drivers) DT_NEEDs them; with the VINTF fragment gone
    # its AServiceManager_isDeclared() check fails and it bails out cleanly (verified in disasm).
    'vendor/bin/vendor.qti.qspmhal-service',
    'vendor/etc/init/vendor.qti.qspmhal-service.rc',
    'vendor/etc/vintf/manifest/vendor.qti.qspmhal-service.xml',
    'vendor/etc/seccomp_policy/qspm.policy',
    'vendor/lib64/vendor.qti.qspmhal-impl.so',

    # qprof: Qualcomm profiler binary. Lives at vendor/bin/qprof and
    # references libQualcommProfiler*.so / libProfileCMetaSharedLib.so
    # which are in vendor/qprof/libs/. extract-utils doesn't emit
    # cc_prebuilt_library_shared modules for files under vendor/qprof/
    # (it treats them as generic file copies), so qprof's shared_libs
    # references are unresolvable. Profiler is a vendor debug tool, not
    # needed for boot. Dropping qprof itself avoids the UND errors;
    # the libs in vendor/qprof/libs/ stay (still installed for any
    # other consumer that loads them via dlopen by full path).
    'vendor/bin/qprof',

    # android.hardware.drm-service.clearkey: AOSP reference clearkey DRM
    # service. Source frameworks/av/drm/mediadrm/plugins/clearkey/aidl/
    # Android.bp builds it as a cc_binary with `vendor: true` and
    # `relative_install_path: "hw"` — i.e., source produces the same
    # vendor/bin/hw/android.hardware.drm-service.clearkey binary that
    # our prebuilt would install. The vendor prebuilt's stale DT_NEEDED
    # on android.hardware.drm-V1-ndk conflicts with the source-built
    # clearkey which links against drm-V2-ndk. Drop our copy; source
    # wins. Path-excluded because the basename is generic.
    'vendor/bin/hw/android.hardware.drm-service.clearkey',
    'vendor/etc/init/android.hardware.drm-service.clearkey.rc',
    'vendor/etc/vintf/manifest/android.hardware.drm-service.clearkey.xml',

    # wpa_supplicant: AOSP source builds it as a `wpa_supplicant_cc_binary`
    # with proprietary: true (vendor partition) under
    # external/wpa_supplicant_8/wpa_supplicant/Android.bp. Source-built
    # version uses wifi.supplicant-V5-ndk; our vendor prebuilt uses V4
    # and triggers a multi-version aidl_interface conflict. Drop our
    # copy; source replaces it.
    'vendor/bin/hw/wpa_supplicant',

    # === vintf manifest XMLs that AOSP source already installs ===
    # Each xml below is in our vendor dump AND is shipped as a
    # vintf_fragments entry by an AOSP / qcom-caf / vendor-qcom-opensource
    # source-side Android.bp. The duplicate triggers
    # `MODULE.TARGET.ETC.<basename> already defined by <source-path>`
    # in kati's base_rules.mk. base_rules.mk aborts at module-
    # registration time, BEFORE prebuilt.mk reads any LOCAL_OVERRIDES
    # — only fix is dropping one side. Drop ours; source is canonical.
    # Path-excluded because XML basenames are too generic to safely
    # blacklist. Bulk-found by walking
    # vendor/etc/vintf/manifest/*.xml and grepping each basename across
    # hardware/, frameworks/, external/, system/, packages/, vendor/qcom/.
    'vendor/etc/vintf/manifest/android.hardware.drm-service.clearkey.xml',  # frameworks/av/drm/mediadrm/plugins/clearkey/aidl/
    'vendor/etc/vintf/manifest/android.hardware.health-service.qti.xml',     # vendor/qcom/opensource/healthd-ext/aidl/
    'vendor/etc/vintf/manifest/android.hardware.sensors-multihal.xml',       # hardware/interfaces/sensors/aidl/multihal/
    'vendor/etc/vintf/manifest/android.hardware.thermal-service.qti.xml',    # hardware/qcom-caf/thermal/
    'vendor/etc/vintf/manifest/android.hardware.usb-service.qti.xml',        # vendor/qcom/opensource/usb/hal/
    'vendor/etc/vintf/manifest/android.hardware.wifi-service.xml',           # hardware/interfaces/wifi/aidl/default/
    'vendor/etc/vintf/manifest/android.hardware.wifi.hostapd.xml',           # external/wpa_supplicant_8/hostapd/
    'vendor/etc/vintf/manifest/android.hardware.wifi.supplicant.xml',        # external/wpa_supplicant_8/wpa_supplicant/aidl/vendor/
    'vendor/etc/vintf/manifest/audioeffectservice_qti.xml',                  # hardware/qcom-caf/sm8750/audio/primary-hal/hal/effects/
    'vendor/etc/vintf/manifest/bluetooth_audio.xml',                         # hardware/interfaces/bluetooth/audio/aidl/default/
    'vendor/etc/vintf/manifest/boot-service.qti.xml',                        # hardware/qcom-caf/bootctrl/aidl/
    'vendor/etc/vintf/manifest/manifest_audiocorehal_default.xml',           # hardware/qcom-caf/sm8750/audio/primary-hal/hal/default/
    'vendor/etc/vintf/manifest/mapper.qti.xml',                              # hardware/qcom-caf/sm8750/display/hal/gralloc/
    'vendor/etc/vintf/manifest/memtrack_qti.xml',                            # hardware/qcom-caf/common/memtrack/
    'vendor/etc/vintf/manifest/power.xml',                                   # hardware/interfaces/power/aidl/default/
    'vendor/etc/vintf/manifest/soundtrigger.qti.xml',                        # vendor/qcom/opensource/audio-hal/st-hal-ar/
    'vendor/etc/vintf/manifest/vendor.qti.hardware.display.allocator-service.xml',  # hardware/qcom/sm7250/display/gralloc/
    'vendor/etc/vintf/manifest/vendor.qti.hardware.display.demura-service.xml',     # hardware/qcom-caf/sm8450/display/oem_services/
    'vendor/etc/vintf/manifest/vendor.qti.hardware.tetheroffload.service.xml',      # hardware/qcom-caf/sm8650/data-ipa-cfg-mgr/ipacm/
    'vendor/etc/vintf/manifest/vendor.qti.hardware.vibrator.service.xml',           # vendor/qcom/opensource/vibrator/aidl/
    'vendor/etc/vintf/manifest/vendor.qti.qspa-service.xml',                        # vendor/qcom/opensource/core-utils-vendor/qspaservice/

    # === Vendor binaries that AOSP source already installs ===
    # Bulk-found by walking proprietary/vendor/bin{,/hw}/ and grepping
    # each filename across hardware/, frameworks/, external/, system/,
    # packages/, vendor/qcom/. Each entry has a source-side Android.bp
    # cc_binary / sh_binary with vendor:true / proprietary:true that
    # installs to the same vendor/bin/(hw/) path. Kati's base_rules.mk
    # aborts at MODULE.TARGET.<class>.<name> duplicate registration —
    # only fix is dropping our copy.
    'vendor/bin/hw/android.hardware.boot-service.qti',                # hardware/qcom-caf/bootctrl/aidl/
    'vendor/bin/hw/android.hardware.health-service.qti',              # vendor/qcom/opensource/healthd-ext/aidl/
    'vendor/bin/hw/android.hardware.sensors-service.multihal',        # hardware/interfaces/sensors/aidl/multihal/
    'vendor/bin/hw/android.hardware.thermal-service.qti',             # hardware/qcom-caf/thermal/
    'vendor/bin/hw/android.hardware.usb-service.qti',                 # vendor/qcom/opensource/usb/hal/
    'vendor/bin/hw/audiohalservice.qti',                              # hardware/qcom-caf/sm8750/audio/primary-hal/hal/service/
    'vendor/bin/hw/vendor.qti.hardware.display.allocator-service',    # hardware/qcom/sm7250/display/gralloc/
    'vendor/bin/hw/vendor.qti.hardware.display.demura-service',       # hardware/qcom-caf/sm8450/display/oem_services/
    'vendor/bin/hw/vendor.qti.hardware.memtrack-service',             # hardware/qcom-caf/common/memtrack/
    'vendor/bin/hw/vendor.qti.hardware.vibrator.service',             # vendor/qcom/opensource/vibrator/aidl/
    'vendor/bin/hw/vendor.qti.qspa-service',                          # vendor/qcom/opensource/core-utils-vendor/qspaservice/
    'vendor/bin/audioadsprpcd',                                       # hardware/qcom-caf/msm8953/audio/adsprpcd/
    'vendor/bin/awk',                                                 # external/one-true-awk/
    'vendor/bin/checkpoint_gc',                                       # system/extras/checkpoint_gc/
    'vendor/bin/cplay',                                               # external/tinycompress/
    'vendor/bin/dumpsys',                                             # frameworks/native/cmds/dumpsys/
    'vendor/bin/hostapd',                                             # external/wpa_supplicant_8/hostapd/
    'vendor/bin/hostapd_cli',                                         # external/wpa_supplicant_8/hostapd/
    'vendor/bin/hs20-osu-client',                                     # external/wpa_supplicant_8/hs20/client/
    'vendor/bin/init.qcom.usb.sh',                                    # vendor/qcom/opensource/usb/etc/
    'vendor/bin/init.qti.display_boot.sh',                            # hardware/qcom/sm7250/display/init/
    'vendor/bin/init.qti.media.sh',                                   # hardware/qcom-caf/sm8250/media/media-prop/
    'vendor/bin/ipacm',                                               # hardware/qcom-caf/sm8450/data-ipa-cfg-mgr/ipacm/
    'vendor/bin/logwrapper',                                          # system/logging/logwrapper/
    'vendor/bin/sg_write_buffer',                                     # external/sg3_utils/
    'vendor/bin/sh',                                                  # external/mksh/
    'vendor/bin/toolbox',                                             # system/core/toolbox/
    'vendor/bin/toybox_vendor',                                       # external/toybox/
    'vendor/bin/vndservice',                                          # frameworks/native/cmds/service/
    'vendor/bin/vndservicemanager',                                   # frameworks/native/cmds/servicemanager/
    'vendor/bin/wpa_cli',                                             # external/wpa_supplicant_8/wpa_supplicant/
    # boringssl_self_test64: external/boringssl/selftest/Android.bp
    #   defines boringssl_self_test_vendor with stem "boringssl_self_test"
    #   that installs to vendor/bin/. The 64 suffix is from extract-utils
    #   suffixing — bulk basename grep missed it because source uses
    #   stem rewriting.
    'vendor/bin/boringssl_self_test64',
    'vendor/etc/boringssl_self_test.zygote64.rc',
    'vendor/etc/boringssl_self_test.zygote64_32.rc',
    'vendor/etc/boringssl_self_test.no_zygote.rc',
    'vendor/etc/boringssl_self_test.zygote32.rc',
    # rkp_factory_extraction_tool64: system/security/provisioner/Android.bp
    #   provides rkp_factory_extraction_tool with extract-utils 64-suffix.
    'vendor/bin/rkp_factory_extraction_tool64',
    # Sibling-path duplicates (the dump has the same binary at both
    # vendor/bin/ and vendor/bin/hw/). Already excluded the bin/hw/
    # variants for these — adding the bin/ variants.
    'vendor/bin/vendor.qti.qspa-service',
    'vendor/bin/hw/hostapd',
    # NOTICE.xml.gz: AOSP build generates this from license metadata
    # at vendor partition assembly time (build/make/core/Makefile:148).
    # Our vendor blob is a stale capture.
    'vendor/etc/NOTICE.xml.gz',
    # aconfig flag database — auto-generated by build/make/core/packaging/
    # flags.mk:168 from PRODUCT_ACONFIG_DECLARATIONS. Stale captures.
    'vendor/etc/aconfig/flag.info',
    'vendor/etc/aconfig/flag.map',
    'vendor/etc/aconfig/flag.val',
    'vendor/etc/aconfig/package.map',
    # build_flags.json — generated by build/make/core/packaging/flags.mk
    # alongside the aconfig files; missed by my earlier scan because it
    # lives at vendor/etc/ root rather than vendor/etc/aconfig/.
    'vendor/etc/build_flags.json',
    # fs_config_dirs/files — auto-generated by build/make/core/Makefile
    # from android_filesystem_config.h. Stale captures.
    'vendor/etc/fs_config_dirs',
    'vendor/etc/fs_config_files',
    # fstab.qcom — already in our device tree at
    # device/nubia/NX809J/rootdir/etc/fstab.qcom and installed via
    # PRODUCT_COPY_FILES in device.mk. Vendor blob collides.
    'vendor/etc/fstab.qcom',
    # linker.config.pb — auto-generated by build/make/core/Makefile
    # from PRODUCT_VENDOR_LINKER_CONFIG. Stale capture.
    'vendor/etc/linker.config.pb',

    # === Bulk vendor/etc/* kati duplicate-target collisions ===
    # Bulk-found by walking proprietary/vendor/etc/ (excluding
    # init/, vintf/manifest/, aconfig/ which are handled above)
    # and grepping each .xml/.conf/.json basename across the
    # escape-hatch allow-list source roots. AOSP source's
    # prebuilt_etc modules install the same content; our copies
    # are stale captures.
    'vendor/etc/a2dp_audio_policy_configuration.xml',  # frameworks/av/services/audiopolicy/config/Android.bp
    'vendor/etc/audio_policy_volumes.xml',  # frameworks/av/services/audiopolicy/config/Android.bp
    'vendor/etc/cgroups.json',  # system/core/libprocessgroup/profiles/Android.bp
    'vendor/etc/default_volume_tables.xml',  # frameworks/av/services/audiopolicy/config/Android.bp
    'vendor/etc/libnfc-nci.conf',  # packages/modules/Nfc/NfcNci/nci/jni/Android.bp
    'vendor/etc/media_codecs.xml',  # frameworks/av/media/libstagefright/data/Android.bp
    'vendor/etc/r_submix_audio_policy_configuration.xml',  # frameworks/av/services/audiopolicy/config/Android.bp
    'vendor/etc/usb_audio_policy_configuration.xml',  # frameworks/av/services/audiopolicy/config/Android.bp
    'vendor/etc/aidl/hfp/hfp_codec_capabilities.xml',  # hardware/interfaces/audio/aidl/default/apex/com.android.hardware.audio/Android.bp
    'vendor/etc/aidl/le_audio/aidl_default_audio_set_configurations.json',  # hardware/interfaces/audio/aidl/default/apex/com.android.hardware.audio/Android.bp
    'vendor/etc/aidl/le_audio/aidl_default_audio_set_scenarios.json',  # hardware/interfaces/audio/aidl/default/apex/com.android.hardware.audio/Android.bp
    'vendor/etc/aidl/le_audio/aidl_audio_set_configurations.bfbs',  # hardware/interfaces/audio/aidl/default/apex/com.android.hardware.audio/Android.bp
    'vendor/etc/aidl/le_audio/aidl_audio_set_scenarios.bfbs',  # hardware/interfaces/audio/aidl/default/apex/com.android.hardware.audio/Android.bp
    'vendor/etc/audio/sku_canoe/audio_policy_volumes.xml',  # frameworks/av/services/audiopolicy/config/Android.bp
    'vendor/etc/camera/fonts.xml',  # frameworks/base/data/fonts/script/Android.bp
    'vendor/etc/mpam/config.json',  # frameworks/av/apex/Android.bp
    'vendor/etc/selinux/vendor_mac_permissions.xml',  # system/sepolicy/mac_permissions/Android.bp
    'vendor/etc/permissions/android.hardware.audio.low_latency.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.bluetooth.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.bluetooth_le.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.camera.concurrent.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.camera.flash-autofocus.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.camera.front.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.camera.full.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.camera.raw.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.consumerir.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.fingerprint.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.hardware_keystore.xml',  # hardware/interfaces/security/keymint/aidl/default/Android.bp
    'vendor/etc/permissions/android.hardware.location.gps.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.nfc.ese.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.nfc.hce.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.nfc.hcef.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.nfc.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.se.omapi.uicc.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.sensor.accelerometer.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.sensor.compass.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.sensor.gyroscope.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.sensor.light.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.sensor.proximity.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.sensor.stepcounter.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.sensor.stepdetector.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.telephony.euicc.mep.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.telephony.gsm.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.telephony.ims.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.usb.accessory.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.usb.host.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.vulkan.compute-0.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.vulkan.level-1.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.wifi.direct.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.wifi.passpoint.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.hardware.wifi.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.software.device_id_attestation.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.software.ipsec_tunnels.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.software.sip.voip.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/android.software.verified_boot.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/com.nxp.mifare.xml',  # frameworks/native/data/etc/Android.bp
    'vendor/etc/permissions/features_com.android.virt.xml',  # packages/modules/Virtualization/build/apex/permissions/Android.bp
    'vendor/etc/permissions/handheld_core_hardware.xml',  # frameworks/native/data/etc/Android.bp

    # === Additional kati duplicate-target collisions found by deeper scan ===
    # vendor/build.prop is generated from PRODUCT_BUILD_PROP_OVERRIDES
    # by frameworks/base build, our blob is a stale stock capture.
    'vendor/build.prop',
    # AOSP-canonical vendor partition support files
    'vendor/etc/group',                                              # hardware/interfaces/audio/2.0/config/Android.bp
    'vendor/etc/passwd',                                             # external/cronet/.../apache-portable-runtime/Android.bp
    'vendor/etc/mkshrc',                                             # external/mksh/Android.bp
    'vendor/etc/public.libraries.txt',                               # system/core/rootdir/Android.bp
    'vendor/etc/ueventd.rc',                                         # system/core/init/Android.bp
    # Charger UI fallback images — system/core/healthd ships defaults
    'vendor/etc/res/images/default/charger/battery_fail.png',        # system/core/healthd/Android.bp
    'vendor/etc/res/images/default/charger/battery_scale.png',       # system/core/healthd/Android.bp
    # Selinux policy files — system/sepolicy generates these per-product
    'vendor/etc/selinux/genfs_labels_version.txt',                   # system/sepolicy/Android.bp
    'vendor/etc/selinux/plat_pub_versioned.cil',                     # system/sepolicy/microdroid/Android.bp
    'vendor/etc/selinux/plat_sepolicy_vers.txt',                     # system/sepolicy/microdroid/Android.bp
    'vendor/etc/selinux/selinux_denial_metadata',                    # system/sepolicy/Android.bp
    'vendor/etc/selinux/vendor_file_contexts',                       # system/sepolicy/contexts/Android.bp
    'vendor/etc/selinux/vendor_hwservice_contexts',                  # system/sepolicy/contexts/Android.bp
    'vendor/etc/selinux/vendor_keystore2_key_contexts',              # system/sepolicy/contexts/Android.bp
    'vendor/etc/selinux/vendor_property_contexts',                   # system/sepolicy/contexts/Android.bp
    'vendor/etc/selinux/vendor_seapp_contexts',                      # system/sepolicy/contexts/Android.bp
    'vendor/etc/selinux/vendor_sepolicy.cil',                        # system/sepolicy/microdroid/Android.bp
    'vendor/etc/selinux/vendor_service_contexts',                    # system/sepolicy/contexts/Android.bp
    'vendor/etc/selinux/vendor_tee_service_contexts',                # system/sepolicy/contexts/Android.bp
    'vendor/etc/selinux/vndservice_contexts',                        # system/sepolicy/contexts/Android.bp
    # eBPF binaries — source builds from C with libbpf
    'vendor/etc/bpf/filterPowerSupplyEvents.o',                      # hardware/interfaces/health/utils/libhealthloop/Android.bp
    # WiFi config — external/wpa_supplicant_8/apex provides default
    'vendor/etc/wifi/wpa_supplicant.conf',                           # external/wpa_supplicant_8/apex/Android.bp
    # vendor/etc/vintf/compatibility_matrix.xml — installed by AOSP system/libhidl/vintfdata
    # generator (vintf_data { type: "device_cm" } at Android.bp:19-24). Our static stock
    # matrix is fed into the generator via DEVICE_MATRIX_FILE in BoardConfig.mk.
    #
    # Three acts of confusion on this path:
    #   Act 1 (iter 173): dropped with speculative rationale "AOSP generates this".
    #     Comment was correct in spirit but missed that the AOSP generator produces an
    #     empty stub unless fed via DEVICE_MATRIX_FILE.
    #   Act 2 (iter 181-182): build halted with checkvintf NAME_NOT_FOUND. Misread the
    #     error as "file not produced", reverted iter 173, restored as a vendor blob.
    #     Created kati duplicate-target collision (AOSP rule at installs.mk:326134 vs
    #     extract-utils rule at installs.mk:348331).
    #   Act 3 (iter 183): correct fix is to keep iter 173's drop AND feed our static
    #     stock matrix via DEVICE_MATRIX_FILE. Single install rule, byte-exact stock
    #     content (preserves <system-sdk>36</system-sdk> assertion), no duplicate.
    'vendor/etc/vintf/compatibility_matrix.xml',
    # Note: drm-service.clearkey, wifi-service, wpa_supplicant,
    # composer-service already in EXCLUDE_PATHS above.

    # === Vendor init .rc files that AOSP source already installs ===
    # Same kati duplicate-target shape, init scripts side. Source-side
    # cc_binary modules ship the .rc as init_rc, which installs to
    # vendor/etc/init/. Drop our duplicates.
    'vendor/etc/init/android.hardware.boot-service.qti.rc',           # hardware/qcom-caf/bootctrl/aidl/
    'vendor/etc/init/android.hardware.health-service.qti.rc',         # vendor/qcom/opensource/healthd-ext/aidl/
    'vendor/etc/init/android.hardware.sensors-service-multihal.rc',   # hardware/interfaces/sensors/aidl/multihal/
    'vendor/etc/init/android.hardware.thermal-service.qti.rc',        # hardware/qcom-caf/thermal/
    'vendor/etc/init/android.hardware.usb-service.qti.rc',            # vendor/qcom/opensource/usb/hal/
    'vendor/etc/init/android.hardware.wifi.supplicant-service.rc',    # external/wpa_supplicant_8/wpa_supplicant/
    'vendor/etc/init/audiohalservice_qti.rc',                         # hardware/qcom-caf/sm8750/audio/primary-hal/hal/service/
    'vendor/etc/init/boringssl_self_test.rc',                         # external/boringssl/selftest/
    'vendor/etc/init/hostapd.android.rc',                             # external/wpa_supplicant_8/hostapd/
    'vendor/etc/init/init.qti.display_boot.rc',                       # hardware/qcom/sm7250/display/init/
    'vendor/etc/init/init.qti.media.rc',                              # hardware/qcom-caf/sm8250/media/media-prop/
    'vendor/etc/init/ipacm.rc',                                       # hardware/qcom-caf/sm8450/data-ipa-cfg-mgr/ipacm/
    'vendor/etc/init/memtrack_qti.rc',                                # hardware/qcom-caf/common/memtrack/
    'vendor/etc/init/qspa_vendor.rc',                                 # vendor/qcom/opensource/core-utils-vendor/qspaframework/
    'vendor/etc/init/vendor.qti.audio-adsprpc-service.rc',            # hardware/qcom-caf/msm8953/audio/adsprpcd/
    'vendor/etc/init/vendor.qti.hardware.display.allocator-service.rc',  # hardware/qcom/sm7250/display/gralloc/
    'vendor/etc/init/vendor.qti.hardware.display.demura-service.rc',     # hardware/qcom-caf/sm8450/display/oem_services/
    'vendor/etc/init/vendor.qti.hardware.vibrator.service.rc',           # vendor/qcom/opensource/vibrator/aidl/
    'vendor/etc/init/vendor.qti.qspa-service.rc',                        # vendor/qcom/opensource/core-utils-vendor/qspaservice/
    'vendor/etc/init/vndservicemanager.rc',                              # frameworks/native/cmds/servicemanager/
    # Note: drm-service.clearkey.rc, wifi-service.rc already in EXCLUDE_PATHS above.

    # android.hardware.wifi-service: AOSP reference wifi HAL service.
    # Source `hardware/interfaces/wifi/aidl/default/Android.bp` defines:
    #   cc_binary {
    #       name: "android.hardware.wifi-service",
    #       proprietary: true,
    #       relative_install_path: "hw",
    #       shared_libs: [..., "android.hardware.wifi-V4-ndk"],
    #   }
    # Source builds the same vendor/bin/hw/android.hardware.wifi-service
    # binary using V4-ndk. Our vendor prebuilt's DT_NEEDED on V3-ndk
    # triggers "depends on multiple versions of the same aidl_interface".
    # Verified: no other vendor blob in the dump references wifi-V3-ndk
    # (full DT_NEEDED scan, only this binary + the V3-ndk lib itself,
    # both being dropped). Sanctioned by Rule 2 escape hatch:
    # source has matching name + proprietary:true + relative_install_path:"hw",
    # source path is under hardware/interfaces/ (not device-specific),
    # device-specific wifi work lives in vendor.qti.hardware.wifi.*-impl.so
    # under vendor/lib64/hw/ which we keep untouched.
    'vendor/bin/hw/android.hardware.wifi-service',
    'vendor/etc/init/android.hardware.wifi-service.rc',
    'vendor/etc/vintf/manifest/android.hardware.wifi-service.xml',

    # libaudiocorehal.{default,qti}: SM8750 audio HAL impls.
    # Source: hardware/qcom-caf/sm8750/audio/primary-hal/hal/{default,
    #   converter}/Android.bp — cc_library_shared with vendor:true and
    #   relative_install_path:"hw". hardware/qcom-caf/sm8750/ is the
    #   chipset-shared source tree used by all SM8750 LineageOS device
    #   trees; Rule 2 escape hatch (post-2026-04-08 addendum) explicitly
    #   covers hardware/qcom-caf/<soc>/. Source guardrails clean: no
    #   nubia/NX809J/canoe references in the source Android.bp.
    'vendor/lib64/hw/libaudiocorehal.default.so',
    'vendor/lib64/hw/libaudiocorehal.qti.so',
    # libaudioplatformconverter.qti: SM8750 audio platform converter.
    # Source: hardware/qcom-caf/sm8750/audio/primary-hal/hal/converter/
    #   Android.bp — cc_library_shared owner:"qti" vendor:true with
    #   latest_android_hardware_audio_core_ndk_shared (V4). Same
    #   chipset-shared source pattern as libaudiocorehal. Vendor
    #   prebuilt of same name shadows via prefer:true and pulls V3
    #   into source's V4 closure → multi-version conflict.
    'vendor/lib64/libaudioplatformconverter.qti.so',
    # libqtigefar: Qualcomm GEF audio extension library. Vendor blob,
    # NO source counterpart. Used by source's audio HAL via runtime
    # dlopen ("libqtigefar.so" string literal in
    # hardware/qcom-caf/sm8750/audio/primary-hal/hal/core/extensions/
    # include/extensions/AudioExtension.h kGefLibrary), NOT link-time.
    # Soong cc_prebuilt module for it has multi-version conflict
    # (V3 direct + V4 via libaudioplatformconverter.qti). Dropping
    # the Soong module is safe — source's audio HAL gracefully
    # degrades when dlopen("libqtigefar.so") fails (audio extensions
    # don't load, baseline audio still works).
    'vendor/lib64/libqtigefar.so',
    # android.hardware.bluetooth.audio_sw: Qualcomm Bluetooth SW audio
    # HAL implementation. Vendor blob in /hw/, NO source counterpart.
    # Same dlopen pattern as libqtigefar — source's audio HAL
    # registers it via runtime dlopen with `mandatory = false` in
    # vendor_audio_interfaces.xml. Soong cc_prebuilt module has
    # unfixable V3+V4 multi-version conflict. Dropping is safe —
    # source's audio HAL skips the Bluetooth SW audio path on dlopen
    # failure; the QTI HW Bluetooth audio path
    # (vendor.qti.hardware.bluetooth.audio*) is independent and
    # remains functional.
    'vendor/lib64/hw/android.hardware.bluetooth.audio_sw.so',
    # com.qti.node.dewarp: Qualcomm camera de-warp node plugin. Vendor
    # blob, no source counterpart. Loaded by camera HAL via runtime
    # dlopen-by-name from vendor/lib64/camera/components/. Has BOTH
    # graphics.allocator-V1-ndk and -V2-ndk in its DT_NEEDED — single
    # binary built against two AIDL versions, Soong refuses. Drop the
    # Soong cc_prebuilt module; install via PRODUCT_COPY_FILES (see
    # device.mk).
    'vendor/lib64/camera/components/com.qti.node.dewarp.so',
    # vendor.qti.hardware.display.composer-service: SM8750 display
    # composer HAL service binary. Source: hardware/qcom-caf/sm8750/
    #   display/hal/composer/Android.bp — cc_binary with vendor:true
    #   relative_install_path:"hw". Escape hatch (qcom-caf/<soc>/)
    #   guardrails clean: no nubia/NX809J/canoe references in source.
    # Vendor prebuilt's stale composer3-V3 / display.config-V12 deps
    # conflict with source's V4 / V13 build → multi-version. Drop our
    # copy; source provides the vendor binary.
    'vendor/bin/hw/vendor.qti.hardware.display.composer-service',
    'vendor/etc/init/vendor.qti.hardware.display.composer-service.rc',
    'vendor/etc/vintf/manifest/vendor.qti.hardware.display.composer-service3_v4.xml',

    # === SPU TEE keymint cluster ===
    # Nubia/Qualcomm SPU (Secure Processing Unit) keymint backend.
    # The libs and service binaries DT_NEED a tangled mix of
    # android.hardware.security.{keymint,sharedsecret}-V*-ndk versions:
    #   libspukeymint.so          -> sharedsecret-V2 (orphan, source frozen at V1)
    #   libspukeymintprovision.so -> keymint-V2 (source has [1,2,3,4])
    #   keymint-service-spu-qti   -> keymint-V4, sharedsecret-V2
    # libspukeymintprovision (V2) statically linked into spu-qti binary
    # which also references V4 → "depends on multiple versions of the
    # same aidl_interface" Soong error. PRODUCT_COPY_FILES bypass
    # installs the entire cluster directly to /vendor/{lib64,bin*}/,
    # avoiding Soong's module system entirely. At runtime, the linker
    # resolves DT_NEEDED for V2/V4 separately from /system/lib64/
    # (source builds versions:[1,2,3,4] for keymint, V1 for sharedsecret)
    # — V1/V2 sharedsecret mismatch is irrelevant if AOSP allows the
    # vendor process to find the V2 .so via the PCF-installed copy at
    # /vendor/lib64/.
    #
    # Has no source counterpart; preserves SPU keymint functionality
    # which is non-trivial to replace.
    'vendor/lib64/libspukeymint.so',
    'vendor/lib64/libspukeymintdeviceutils.so',
    'vendor/lib64/libspukeymintprovision.so',
    'vendor/lib64/libspukeymintutils.so',
    'vendor/lib64/hw/libspuqtigatekeeper.so',
    'vendor/bin/spu_install_keybox',
    'vendor/bin/hw/android.hardware.gatekeeper-service-spu-qti',
    'vendor/bin/hw/android.hardware.security.keymint-service-spu-qti',
    'vendor/bin/hw/android.hardware.weaver-service-spu-qti',
}

EXCLUDE_BASENAMES = {
    # === Android Automotive (AAOS) cluster — phone, not a car ===
    # The Red Magic 11 Pro is a phone. The dump contains 4 AAOS blobs
    # forming a self-contained DT_NEEDED subgraph (verified: no
    # non-AAOS ELF in the dump references any of these). The watchdog
    # one collides on Soong analysis with AOSP's
    # packages/services/Car/cpp/watchdog/aidl source-built module
    # (system) vs our vendor prebuilt (vendor) — "partition is
    # different". The other three would either trigger the same class
    # of error or have unresolved DT_NEEDED on the watchdog after it's
    # excluded. Drop all four; the source-side AAOS modules compile to
    # enabled:false on non-AAOS products. Risk-free on a phone.
    'android.automotive.watchdog-V2-ndk.so',
    'android.hardware.automotive.vehicle@2.0.so',
    'android.hardware.automotive.vehicle@2.0-manager-lib.so',
    'vendor.qti.hardware.automotive.vehicle@1.0.so',

    # === AOSP AIDL stable interfaces (android.hardware.*-V*-ndk.so) ===
    # AIDL stable ABI: android.hardware.*-V*-ndk libraries are AOSP-defined
    # stable interfaces. AOSP source under hardware/interfaces/ builds
    # them as system modules; vendor processes resolve them via the
    # documented vendor->system AIDL -ndk linker namespace exemption set
    # up in system/core/rootdir/etc/ld.config.*.txt. Vendor-side
    # prebuilts of these are stale captures from the stock dump and
    # conflict with the source-built canonical versions on Soong analysis
    # ("partition is different: system != vendor").
    #
    # Per-version verified by verify_aidl_versions.py against source
    # aidl_interface { versions: [...] } blocks; only versions that source
    # actually builds (frozen ∪ unfrozen-current) are excluded. Orphan
    # versions whose source has dropped support stay in the dump because
    # vendor consumers DT_NEEDed them and would fail at process load
    # otherwise (see KEEP entry below).
    #
    # See:
    #   device/nubia/NX809J/.aidl_verify.txt — full DROP/KEEP/UNKNOWN table
    #   device/nubia/NX809J/verify_aidl_versions.py — reusable verifier
    'android.hardware.audio.common-V2-ndk.so',
    'android.hardware.audio.common-V4-ndk.so',
    'android.hardware.audio.core.sounddose-V1-ndk.so',
    'android.hardware.audio.core.sounddose-V3-ndk.so',
    'android.hardware.audio.core-V3-ndk.so',
    'android.hardware.audio.effect-V3-ndk.so',
    'android.hardware.biometrics.common-V4-ndk.so',
    'android.hardware.biometrics.fingerprint-V4-ndk.so',
    'android.hardware.bluetooth.audio-V3-ndk.so',
    'android.hardware.bluetooth.audio-V5-ndk.so',
    'android.hardware.bluetooth.finder-V1-ndk.so',
    'android.hardware.bluetooth.lmp_event-V1-ndk.so',
    'android.hardware.bluetooth.ranging-V2-ndk.so',
    'android.hardware.bluetooth-V1-ndk.so',
    'android.hardware.boot-V1-ndk.so',
    'android.hardware.camera.common-V1-ndk.so',
    'android.hardware.camera.device-V2-ndk.so',
    'android.hardware.camera.device-V3-ndk.so',
    'android.hardware.camera.metadata-V2-ndk.so',
    'android.hardware.camera.metadata-V3-ndk.so',
    'android.hardware.camera.provider-V3-ndk.so',
    'android.hardware.common.fmq-V1-ndk.so',
    'android.hardware.common-V2-ndk.so',
    'android.hardware.drm.common-V1-ndk.so',
    'android.hardware.drm-V1-ndk.so',
    'android.hardware.gatekeeper-V1-ndk.so',
    'android.hardware.gnss-V4-ndk.so',
    'android.hardware.graphics.allocator-V1-ndk.so',
    'android.hardware.graphics.allocator-V2-ndk.so',
    'android.hardware.graphics.common-V5-ndk.so',
    'android.hardware.graphics.common-V6-ndk.so',
    'android.hardware.graphics.composer3-V2-ndk.so',
    'android.hardware.graphics.composer3-V3-ndk.so',
    'android.hardware.graphics.composer3-V4-ndk.so',
    'android.hardware.health-V1-ndk.so',
    'android.hardware.health-V4-ndk.so',
    'android.hardware.identity-V5-ndk.so',
    'android.hardware.ir-V1-ndk.so',
    'android.hardware.keymaster-V3-ndk.so',
    'android.hardware.keymaster-V4-ndk.so',
    'android.hardware.light-V2-ndk.so',
    'android.hardware.media.bufferpool2-V2-ndk.so',
    'android.hardware.media.c2-V1-ndk.so',
    'android.hardware.memtrack-V1-ndk.so',
    'android.hardware.nfc-V2-ndk.so',
    'android.hardware.power.stats-V2-ndk.so',
    'android.hardware.power-V6-ndk.so',
    'android.hardware.radio.config-V4-ndk.so',
    'android.hardware.radio.data-V4-ndk.so',
    'android.hardware.radio.messaging-V4-ndk.so',
    'android.hardware.radio.modem-V4-ndk.so',
    'android.hardware.radio.network-V4-ndk.so',
    'android.hardware.radio.sap-V1-ndk.so',
    'android.hardware.radio.sim-V4-ndk.so',
    'android.hardware.radio-V4-ndk.so',
    'android.hardware.radio.voice-V4-ndk.so',
    'android.hardware.secure_element-V1-ndk.so',
    'android.hardware.security.keymint-V1-ndk.so',
    'android.hardware.security.keymint-V2-ndk.so',
    'android.hardware.security.keymint-V4-ndk.so',
    'android.hardware.security.rkp-V3-ndk.so',
    'android.hardware.security.secureclock-V1-ndk.so',
    'android.hardware.sensors-V3-ndk.so',
    'android.hardware.soundtrigger3-V1-ndk.so',
    'android.hardware.tetheroffload-V1-ndk.so',
    'android.hardware.thermal-V1-ndk.so',
    'android.hardware.thermal-V3-ndk.so',
    'android.hardware.usb-V1-ndk.so',
    'android.hardware.vibrator-V2-ndk.so',
    'android.hardware.weaver-V2-ndk.so',
    'android.hardware.wifi.common-V1-ndk.so',
    'android.hardware.wifi.common-V2-ndk.so',
    'android.hardware.wifi.hostapd-V2-ndk.so',
    'android.hardware.wifi.hostapd-V3-ndk.so',
    'android.hardware.wifi.supplicant-V3-ndk.so',
    'android.hardware.wifi.supplicant-V4-ndk.so',
    'android.hardware.wifi-V3-ndk.so',

    # === android.media.*-V*-ndk AIDL stable interfaces ===
    # system/hardware/interfaces/media/Android.bp — verified by
    # verify_aidl_versions.py with vendor_available + vintf.
    'android.media.audio.common.types-V2-ndk.so',
    'android.media.audio.common.types-V4-ndk.so',
    'android.media.audio.eraser.types-V1-ndk.so',
    'android.media.soundtrigger.types-V1-ndk.so',

    # === android.frameworks.*-V*-ndk AIDL stable interfaces ===
    # frameworks/hardware/interfaces/ — verified by verify_aidl_versions.py
    # with vendor_available + vintf.
    'android.frameworks.location.altitude-V2-ndk.so',
    'android.frameworks.sensorservice-V1-ndk.so',

    # === HIDL interface prebuilts (com.dsi.ant) ===
    # com.dsi.ant@1.0: external/ant-wireless/hidl/interfaces/ant/1.0/
    #   Android.bp — hidl_interface, source builds vendor variant.
    #   Red Magic 11 Pro doesn't use ANT+ but the dump captured it;
    #   the source-built version handles vendor consumption.
    'com.dsi.ant@1.0.so',

    # === AOSP HIDL stable interfaces (android.hardware.*@N.M.so) ===
    # Verified per-basename: each is a hidl_interface { ... } block
    # under hardware/interfaces/ in source. HIDL stable interfaces are
    # vendor-consumable by AIDL/HIDL contract — vendor processes
    # resolve to /system/lib*/<basename> via the linker namespace
    # exemption. The HAL impls (in vendor/lib*/hw/) link against these
    # interface SOs and stay in the dump unchanged.
    'android.hardware.audio.common@5.0.so',
    'android.hardware.authsecret@1.0.so',
    'android.hardware.bluetooth.audio@2.0.so',
    'android.hardware.bluetooth.audio@2.1.so',
    'android.hardware.bluetooth@1.0.so',
    'android.hardware.boot@1.0.so',
    'android.hardware.boot@1.1.so',
    'android.hardware.gatekeeper@1.0.so',
    'android.hardware.graphics.allocator@2.0.so',
    'android.hardware.graphics.allocator@3.0.so',
    'android.hardware.graphics.allocator@4.0.so',
    'android.hardware.graphics.bufferqueue@1.0.so',
    'android.hardware.graphics.bufferqueue@2.0.so',
    'android.hardware.graphics.common@1.0.so',
    'android.hardware.graphics.common@1.1.so',
    'android.hardware.graphics.common@1.2.so',
    'android.hardware.graphics.composer@2.1.so',
    'android.hardware.graphics.composer@2.2.so',
    'android.hardware.graphics.composer@2.3.so',
    'android.hardware.graphics.mapper@2.0.so',
    'android.hardware.graphics.mapper@2.1.so',
    'android.hardware.graphics.mapper@3.0.so',
    'android.hardware.graphics.mapper@4.0.so',
    'android.hardware.health@1.0.so',
    'android.hardware.health@2.0.so',
    'android.hardware.health@2.1.so',
    'android.hardware.keymaster@3.0.so',
    'android.hardware.keymaster@4.0.so',
    'android.hardware.keymaster@4.1.so',
    'android.hardware.media.bufferpool@2.0.so',
    'android.hardware.media.c2@1.0.so',
    'android.hardware.media.omx@1.0.so',
    'android.hardware.media@1.0.so',
    'android.hardware.power@1.0.so',
    'android.hardware.power@1.1.so',
    'android.hardware.power@1.2.so',
    'android.hardware.radio@1.0.so',
    'android.hardware.radio@1.1.so',
    'android.hardware.renderscript@1.0.so',
    'android.hardware.sensors@1.0.so',
    'android.hardware.sensors@2.0.so',
    'android.hardware.sensors@2.1.so',
    'android.hardware.thermal@1.0.so',
    'android.hardware.thermal@2.0.so',

    # === Qualcomm HIDL stable interfaces (vendor.*@N.M.so) ===
    # 37 hidl_interface modules under vendor/qcom/opensource/interfaces/.
    # Verified per-basename via grep + decl-check (hidl_interface block
    # exists with the matching name). The remaining ~85 vendor.qti.*@*.so
    # basenames in the dump have NO source counterpart and are KEPT.
    'vendor.display.config@1.0.so',
    'vendor.display.config@1.1.so',
    'vendor.display.config@1.2.so',
    'vendor.display.config@1.3.so',
    'vendor.display.config@1.4.so',
    'vendor.display.config@1.5.so',
    'vendor.display.config@1.6.so',
    'vendor.display.config@1.7.so',
    'vendor.display.config@1.8.so',
    'vendor.display.config@1.9.so',
    'vendor.display.config@1.10.so',
    'vendor.display.config@1.11.so',
    'vendor.display.config@2.0.so',
    'vendor.qti.hardware.bluetooth_audio@2.0.so',
    'vendor.qti.hardware.bluetooth_audio@2.1.so',
    'vendor.qti.hardware.display.allocator@1.0.so',
    'vendor.qti.hardware.display.allocator@3.0.so',
    'vendor.qti.hardware.display.composer@1.0.so',
    'vendor.qti.hardware.display.composer@2.0.so',
    'vendor.qti.hardware.display.mapper@1.0.so',
    'vendor.qti.hardware.display.mapper@1.1.so',
    'vendor.qti.hardware.display.mapper@2.0.so',
    'vendor.qti.hardware.display.mapper@3.0.so',
    'vendor.qti.hardware.display.mapper@4.0.so',
    'vendor.qti.hardware.display.mapperextensions@1.0.so',
    'vendor.qti.hardware.display.mapperextensions@1.1.so',
    'vendor.qti.hardware.display.mapperextensions@1.2.so',
    'vendor.qti.hardware.display.mapperextensions@1.3.so',
    'vendor.qti.hardware.perf@2.0.so',
    'vendor.qti.hardware.perf@2.1.so',
    'vendor.qti.hardware.perf@2.2.so',
    'vendor.qti.hardware.servicetracker@1.0.so',
    'vendor.qti.hardware.servicetracker@1.1.so',
    'vendor.qti.hardware.systemhelper@1.0.so',
    'android.system.wifi.keystore@1.0.so',
    'android.frameworks.sensorservice@1.0.so',  # frameworks/hardware/interfaces/sensorservice/1.0
    # android.hidl.* core HIDL helpers — system/libhidl/transport/
    'android.hidl.allocator@1.0.so',
    'android.hidl.memory@1.0.so',
    'android.hidl.memory.token@1.0.so',
    'android.hidl.safe_union@1.0.so',
    'android.hidl.token@1.0.so',
    # libutilscallstack: system/core/libutils/Android.bp via
    #   libutils_defaults > libutils_defaults_nodeps (vendor_available).
    'libutilscallstack.so',

    # === XMLs/JSONs that exist in BOTH our dump and a Soong namespace ===
    # NS class conflicts: hardware/qcom-caf/sm8750/ provides each of
    # these as a Soong-installed prebuilt_etc, our dump has the same
    # basename. Drop our copies; source wins. Bulk-extracted by walking
    # the source tree and matching basenames against the dump.
    'Hapticsconfig.xml',
    'IPACM_Filter_cfg.xml',
    'IPACM_cfg.xml',
    'audio_effects.conf',
    'audio_effects.xml',
    'audio_effects_config.xml',
    'audio_module_config_primary.xml',
    'audio_policy_configuration.xml',
    'audioeffectservice_qti.xml',                # session 1 line 2
    'backlight_calib_nt37801_amoled_cmd_mode_dsi_csot_panel_with_DSC_CPHY.xml',
    'backlight_calib_r66451_amoled_cmd_mode_dsi_visionox_panel_with_DSC.xml',
    'backlight_calib_vtdr6130_amoled_cmd_mode_dsi_visionox_panel_with_DSC.xml',
    'bluetooth_qti_audio_policy_configuration.xml',
    'bluetooth_qti_hearing_aid_audio_policy_configuration.xml',
    'camera_alignments.json',
    'card-defs.xml',
    'clstc_config_library.xml',
    'cpu_alignments.json',
    'default_alignments.json',
    'display_alignments.json',
    'display_id_4630946916234099603.xml',
    'formats.json',
    'graphics_alignments.json',
    'manifest_audiocorehal_default.xml',         # session 1 line 59
    'mapper.qti.xml',
    'media_codecs_c2_audio.xml',
    'media_codecs_vendor_audio.xml',
    'mem_logger_config.xml',
    'microphone_characteristics.xml',
    'qdcm_calib_data_Sharp_2k_cmd_mode_qsync_dsi_panel.json',
    'qdcm_calib_data_Sharp_2k_video_mode_qsync_dsi_panel.json',
    'qdcm_calib_data_Sharp_4k_cmd_mode_dsc_dsi_panel.json',
    'qdcm_calib_data_Sharp_4k_video_mode_dsc_dsi_panel.json',
    'qdcm_calib_data_Sharp_qhd_cmd_mode_dsi_panel.json',
    'qdcm_calib_data_Sharp_qhd_video_mode_dsi_panel.json',
    'qdcm_calib_data_nt36672e_lcd_video_mode_dsi_novatek_panel_with_DSC.json',
    'qdcm_calib_data_nt36672e_lcd_video_mode_dsi_novatek_panel_without_DSC.json',
    'qdcm_calib_data_nt37801_amoled_cmd_mode_dsi_csot_panel_with_DSC.json',
    'qdcm_calib_data_nt37801_amoled_cmd_mode_dsi_csot_panel_with_DSC_CPHY.json',
    'qdcm_calib_data_nt37801_amoled_video_mode_dsi_csot_panel_with_DSC.json',
    'qdcm_calib_data_nt37801_amoled_video_mode_dsi_csot_panel_with_DSC_CPHY.json',
    'qdcm_calib_data_r66451_amoled_cmd_mode_dsi_visionox_panel_with_DSC.json',
    'qdcm_calib_data_r66451_amoled_cmd_mode_dsi_visionox_panel_without_DSC.json',
    'qdcm_calib_data_r66451_amoled_video_mode_dsi_visionox_panel_with_DSC.json',
    'qdcm_calib_data_r66451_amoled_video_mode_dsi_visionox_panel_without_DSC.json',
    'qdcm_calib_data_sharp_1080p_cmd_mode_dsi_panel.json',
    'qdcm_calib_data_vtdr6130_amoled_cmd_mode_dsi_visionox_panel_with_DSC.json',
    'qdcm_calib_data_vtdr6130_amoled_qsync_cmd_mode_dsi_visionox_panel_with_DSC.json',
    'qdcm_calib_data_vtdr6130_amoled_qsync_video_mode_dsi_visionox_panel_with_DSC.json',
    'qdcm_calib_data_vtdr6130_amoled_video_mode_dsi_visionox_panel_with_DSC.json',
    'quasar_config.xml',
    'sdm_display_resolution_extn.xml',
    'smomo_setting.xml',
    'snapdragon_color_libs_config.xml',
    'ubwc_alignments.json',
    'usecaseKvManager.xml',
    'vendor.qti.hardware.display.allocator-service.xml',
    'vendor.qti.hardware.display.demura-service.xml',
    'vendor.qti.hardware.tetheroffload.service.xml',
    'vendor_audio_interfaces.xml',
    'video_alignments.json',

    # === Build-system helpers ===
    # libaconfig_storage_read_api_cc: build/make/tools/aconfig/
    #   aconfig_storage_read_api/Android.bp (vendor_available). Aconfig
    #   feature flag storage reader.
    'libaconfig_storage_read_api_cc.so',

    # === Orphan AIDL versions installed via PRODUCT_COPY_FILES bypass ===
    # These ARE excluded from extract-utils (so no Soong prebuilt module
    # gets generated, no PART conflict) but the file is still installed
    # to /vendor/lib64/ via PRODUCT_COPY_FILES in device.mk. This is the
    # documented escape hatch when Soong PART (system declares the name)
    # and runtime DT_NEEDED (vendor consumer fails dlopen) are mutually
    # exclusive. See device.mk "Soong-bypass installs" block.
    #
    # android.hardware.security.sharedsecret-V2-ndk:
    #   Source frozen at V1 (hardware/interfaces/security/sharedsecret/
    #   aidl/Android.bp versions:["1"] frozen:true); libspukeymint.so
    #   DT_NEEDs V2.
    'android.hardware.security.sharedsecret-V2-ndk.so',
    # libNubiaImageAlgorithmVD: Nubia proprietary image algorithm.
    #   No source. Soong VARIANT error: needs a vendor variant of
    #   libskia which AOSP only builds for system. Bypassed via
    #   PRODUCT_COPY_FILES in device.mk so the file lands at
    #   /vendor/lib64/ without going through Soong's variant matrix.
    #   See device.mk "Soong-bypass installs" block.
    'libNubiaImageAlgorithmVD.so',
    # libhapticgenerator: vendor audio effect plugin in soundfx/.
    #   Soong VIS error: tries to link against source-built
    #   libvibratorutils whose visibility list doesn't include
    #   //vendor/nubia/NX809J. Audio effect plugins are dlopen'd by
    #   AudioFlinger via /vendor/lib64/soundfx/<name>, so PRODUCT_COPY_FILES
    #   bypass works the same way as for sharedsecret-V2 and
    #   libNubiaImageAlgorithmVD.
    'libhapticgenerator.so',

    # === android.system.*-V*-ndk AIDL stable interfaces ===
    # Same architectural reasoning as android.hardware.*: AIDL stable
    # ABI, source-built canonical (system/hardware/interfaces/), vendor
    # consumers resolve via the linker namespace exemption. Per-version
    # verified by verify_aidl_versions.py.
    'android.system.keystore2-V1-ndk.so',          # versions=[1..5] frozen
    'android.system.net.netd-V1-ndk.so',           # versions=[1] frozen
    'android.system.suspend-V1-ndk.so',            # versions=[1] frozen

    # === Qualcomm common-source libraries ===
    # cc_library_shared modules in hardware/qcom-caf/common/ (not AIDL)
    # that declare vendor_available: true, so source builds the vendor
    # variant. Stale vendor prebuilts conflict on Soong analysis
    # (system_ext != vendor). Per-case verified against the source
    # cc_library_shared block.
    #
    # libqti_vndfwk_detect: hardware/qcom-caf/common/fwk-detect/Android.bp
    #   cc_library_shared { name: "libqti_vndfwk_detect",
    #     system_ext_specific: true, vendor_available: true }
    # libvndfwk_detect_jni.qti: same Android.bp, same shape.
    # The _vendor-suffixed variants (libqti_vndfwk_detect_vendor.so,
    # libvndfwk_detect_jni.qti_vendor.so) are KEEP — distinct files with
    # no source counterpart by that exact basename.
    'libqti_vndfwk_detect.so',
    'libvndfwk_detect_jni.qti.so',

    # === AOSP biometrics common config helper ===
    # hardware/interfaces/biometrics/common/config/Android.bp
    #   cc_library { name: "android.hardware.biometrics.common.config",
    #     vendor_available: true }
    # Source builds vendor variant. Same drop pattern as
    # libqti_vndfwk_detect.
    'android.hardware.biometrics.common.config.so',

    # === Core AOSP framework libraries (vendor_available) ===
    # Non-AIDL framework helpers built from AOSP source with
    # vendor_available: true. Stale vendor prebuilts in the dump
    # conflict with the source-built vendor variant on Soong analysis.
    # Vendor consumers link against the source-built version at runtime
    # via the vendor linker namespace.
    #
    # libhidltransport: system/libhidl/Android.bp — core HIDL transport.
    # libcodec2: frameworks/av/media/codec2/core/Android.bp — codec2
    #   framework core (not a codec impl; vendor codec impls like
    #   libcodec2_qti.so link against this).
    # libRSCpuRef: frameworks/rs/cpu_ref/Android.bp — RenderScript CPU
    #   fallback runtime (vendor_available).
    # libwifi-system-iface: frameworks/opt/net/wifi/libwifi_system_iface/
    #   Android.bp — system-side wifi system interface that WifiManager and
    #   wpa_supplicant link against. NOT a wifi HAL impl (those are
    #   vendor.qti.hardware.wifi.*-impl.so under vendor/lib*/hw/).
    #   vendor_available: true. Was wrongly dropped in session 1; this
    #   time it's the source-PART resolution, which is correct.
    # android.hardware.sensors@2.0-ScopedWakelock: hardware/interfaces/
    #   sensors/common/default/2.X/multihal/Android.bp — RAII wakelock
    #   helper used by the sensors multihal infrastructure. NOT a sensors
    #   HAL impl (those are sensors.<chip>.so or
    #   android.hardware.sensors-service.multihal binary).
    # libstagefright_aidl_bufferpool2: hardware/interfaces/media/bufferpool/
    #   aidl/default/Android.bp — media bufferpool aidl helper, not a
    #   codec impl.
    'libhidltransport.so',
    'libcodec2.so',
    'libRSCpuRef.so',
    'libwifi-system-iface.so',
    'android.hardware.sensors@2.0-ScopedWakelock.so',
    'libstagefright_aidl_bufferpool2.so',
    # libdmabufheap: system/memory/libdmabufheap/Android.bp — DMA-BUF
    #   heap allocator helper (vendor_available, recovery_available).
    'libdmabufheap.so',
    # libpsi: system/memory/lmkd/libpsi/Android.bp — pressure stall
    #   information helper for low-memory killer (vendor_available).
    'libpsi.so',
    # libRSDriver: frameworks/rs/Android.bp — RenderScript driver core
    #   (vendor_available).
    'libRSDriver.so',
    # libRS_internal: frameworks/rs/Android.bp — RenderScript internal
    #   runtime (vendor_available, sibling of libRSDriver/libRSCpuRef).
    'libRS_internal.so',

    # === Qualcomm common-source AIDL interfaces ===
    # qti-audio-types-aidl-V1-ndk: vendor/qcom/opensource/commonsys-intf/
    #   audio/hal_adapter/Android.bp — aidl_interface vendor_available,
    #   system_ext_specific, versions_with_info[1]. Source builds V1.
    #   Was wrongly dropped in session 1 (line 63 of audit).
    'qti-audio-types-aidl-V1-ndk.so',
    # libstagefright_bufferpool@2.0.1: frameworks/av/media/module/
    #   bufferpool/2.0/Android.bp (vendor_available).
    'libstagefright_bufferpool@2.0.1.so',
    # libavservices_minijail: frameworks/av/media/module/minijail/
    #   Android.bp (vendor_available). Minijail wrapper for AV services.
    'libavservices_minijail.so',
    # libmedia_omx: frameworks/av/media/libmedia/Android.bp
    #   (vendor_available, double_loadable). OMX media client lib.
    'libmedia_omx.so',
    # libeffectsconfig: frameworks/av/media/libeffects/config/Android.bp
    #   (vendor_available). Audio effects config parser helper, NOT an
    #   audio HAL impl. Was wrongly dropped in session 1 (line 13).
    'libeffectsconfig.so',
    # libnetutils: system/core/libnetutils/Android.bp (vendor_available).
    'libnetutils.so',
    # libmediautils_vendor: frameworks/av/media/utils/Android.bp
    #   (vendor_available — explicit comment "required for platform/
    #   hardware/interfaces").
    'libmediautils_vendor.so',
    # libhwbinder: system/libhwbinder/Android.bp (vendor_available).
    'libhwbinder.so',
    # libbinderdebug: frameworks/native/libs/binderdebug/Android.bp
    #   (vendor_available).
    'libbinderdebug.so',
    # libcodec2_hal_common: frameworks/av/media/codec2/hal/common/
    #   Android.bp (vendor_available, double_loadable).
    'libcodec2_hal_common.so',
    # libstagefright_bufferqueue_helper: frameworks/av/media/module/
    #   bqhelper/Android.bp (vendor_available).
    'libstagefright_bufferqueue_helper.so',
    # libcodec2_aidl + libcodec2_aidl_noisurface: frameworks/av/media/
    #   codec2/hal/aidl/Android.bp (vendor_available).
    'libcodec2_aidl.so',
    'libcodec2_aidl_noisurface.so',
    # libcodec2_vndk: frameworks/av/media/codec2/vndk/Android.bp
    #   (vendor_available).
    'libcodec2_vndk.so',
    # libui: frameworks/native/libs/ui/Android.bp (vendor_available,
    #   double_loadable). Core UI library, NOT a graphics HAL impl.
    'libui.so',
    # libion: system/memory/libion/Android.bp via libion_defaults
    #   (vendor_available, product_available, recovery_available).
    'libion.so',
    # libpower: hardware/libhardware_legacy/Android.bp (vendor_available).
    #   Power management helper. Was wrongly dropped in session 1
    #   (line 29).
    'libpower.so',
    # libhardware_legacy: hardware/libhardware_legacy/Android.bp
    #   (vendor_available). Legacy HAL helper.
    'libhardware_legacy.so',
    # libhardware: hardware/libhardware/Android.bp (vendor_available,
    #   recovery_available, host_supported). HAL loading helper.
    'libhardware.so',
    # libflatbuffers-cpp: external/flatbuffers/Android.bp (vendor_available).
    'libflatbuffers-cpp.so',
    # libmedia_helper: frameworks/av/media/libmediahelper/Android.bp
    #   (vendor_available).
    'libmedia_helper.so',
    # android.hardware.biometrics.common.thread: hardware/interfaces/
    #   biometrics/common/thread/Android.bp (vendor_available). Biometrics
    #   thread helper, NOT a biometrics HAL impl.
    'android.hardware.biometrics.common.thread.so',
    # libexif: external/libexif/Android.bp (vendor_available). EXIF
    #   metadata parser, NOT a camera HAL impl. Was wrongly dropped in
    #   session 1 (line 14).
    'libexif.so',
    # libaudioaidlcommon: hardware/interfaces/audio/aidl/common/Android.bp
    #   (vendor_available). Audio AIDL helper. Was wrongly dropped in
    #   session 1 (line 8).
    'libaudioaidlcommon.so',
    # libtinyalsa + libtinyalsav2: external/tinyalsa{,_new}/Android.bp
    #   (vendor_available). ALSA helpers, NOT audio HAL impls. Both
    #   wrongly dropped in session 1 (lines 53-54).
    'libtinyalsa.so',
    'libtinyalsav2.so',
    # libbcinfo: frameworks/compile/libbcc/bcinfo/Android.bp
    #   (vendor_available, double_loadable). Bitcode info, RenderScript
    #   support.
    'libbcinfo.so',
    # libspeexresampler: external/speex/Android.bp (vendor_available).
    'libspeexresampler.so',
    # libnl: external/libnl/Android.bp (vendor_available, host_supported).
    'libnl.so',
    # libandroid_runtime_lazy: frameworks/native/libs/android_runtime_lazy/
    #   Android.bp (vendor_available, recovery_available).
    'libandroid_runtime_lazy.so',
    # libgatekeeper: system/gatekeeper/Android.bp (vendor_available,
    #   host_supported). Gatekeeper helper library — NOT a gatekeeper
    #   HAL impl (those are vendor/lib64/hw/android.hardware.gatekeeper@
    #   1.0-impl.so and vendor/bin/hw/android.hardware.gatekeeper-service-
    #   qti, both still present in the dump). Was wrongly dropped in
    #   session 1 (line 15).
    'libgatekeeper.so',
    # libkeymaster_messages: system/keymaster/Android.bp via
    #   keymaster_defaults (vendor_available). Keymaster wire format
    #   helper, NOT a keymaster HAL impl (those are
    #   vendor/bin/hw/android.hardware.security.keymint-service-qti and
    #   similar, still present). Session 1 audit line 20.
    'libkeymaster_messages.so',
    # libultrahdr: external/libultrahdr/Android.bp (vendor_available,
    #   host_supported). Ultra HDR image encoder/decoder lib.
    'libultrahdr.so',
    # libexpat: external/expat/Android.bp (vendor_available,
    #   product_available). XML parser.
    'libexpat.so',

    # === Bulk-verified by verify_cc_libs.py ===
    # 21 cc_library / cc_library_shared modules whose source declares
    # vendor_available: true (directly or via cc_defaults inheritance).
    # Each was confirmed by the bulk verifier; the per-basename source
    # paths are in .cc_lib_verify.txt. None of these are HAL impls
    # (verifier filters out vendor/lib*/hw/, HAL-impl basename
    # patterns, and APK-bundled libs). Many were wrongly dropped in
    # session 1 — this time the drop is the correct source-PART
    # resolution.
    'android.hidl.token@1.0-utils.so',
    'libaudio_aidl_conversion_common_ndk.so',  # session 1 line 9
    'libaudioroute.so',                         # session 1 line 10
    'libcap.so',
    'libcompiler_rt.so',
    'libcurl.so',
    'libdrm.so',
    'libgralloctypes.so',                       # session 1 line 16
    'libimage_io.so',                           # session 1 line 17
    'libjpegdecoder.so',                        # session 1 line 18
    'libjpegencoder.so',                        # session 1 line 19
    'libjpeg.so',
    'liblzma.so',
    'libminijail.so',
    'libpng.so',
    'libsqlite.so',
    'libstagefright_foundation.so',
    # NOTE: libtensorflowlite_c.so was previously excluded under the speculative
    # rationale "session 1 line 52" (i.e. early-iter dump-of-source-equivalent).
    # Iter 204 verification revealed:
    # - Nubia's libtensorflowlite_c.so defines TfLiteXNNPackDelegateCreate/Delete/
    #   OptionsDefault@@VERS_1.0 — XNNPACK delegate symbols that source-built
    #   libtensorflowlite_c.so does NOT export
    # - 3 vendor consumers (libVoiceSdk, libcapiv2uvvendor, liblistensoundmodel2vendor)
    #   need these symbols via DT_NEEDED libtensorflowlite_c.so
    # - libcapiv2uvvendor is loaded as USER_VERIFICATION wake-word arm_ss_module
    #   in vendor/etc/audio/sku_*/resourcemanager_*.xml — NOT dead weight
    #
    # Restoring with FIX_SONAME (rename to libtensorflowlite_c_vendor.so) to
    # avoid kati duplicate-target collision with source-built libtensorflowlite_c.so.
    # Consumer DT_NEEDED entries are patched via blob_fixup.replace_needed in
    # extract-files.py to point at the renamed file. Mirrors dodge sm8750-common's
    # exact pattern (verified iter 204).
    #
    # iter 194 verification gap: declared this a "stub" based on zero TfLiteInterpreter*
    # matches. Should have checked the FULL exported symbol set, not just expected
    # symbols. Process learning codified for the iter-173 audit.
    #
    # Synthetic file libtensorflowlite_c_vendor.so is the patchelf-renamed copy
    # created at iter 204 bootstrap (cp + patchelf --set-soname). It exists in
    # the proprietary tree but is the FIX_SONAME destination, not an independent
    # source — exclude it from the closure walk to avoid duplicate emission.
    'libtensorflowlite_c_vendor.so',
    'libtinyxml2.so',
    'libvibratorutils.so',                      # session 1 line 55. Vibrator
                                                 # helper, NOT a vibrator HAL impl.
    # NOTE: libVoiceSdk.so was dropped at iter 202 under a flawed dead-weight
    # verification (the 4-criteria template was incomplete: missed audio
    # resource manager XML configs in vendor/etc/audio/, AND used a brace-
    # expansion find pattern that produced false negatives in this shell).
    # Iter 203's Soong analysis revealed missed consumers (libcapiv2uvvendor,
    # liblistensoundmodel2vendor) and iter 204's investigation revealed:
    # - libcapiv2uvvendor is loaded by audio resource manager XMLs as the
    #   USER_VERIFICATION wake-word arm_ss_module — actively used at runtime
    # - Nubia's libtensorflowlite_c.so DOES export the XNNPACK delegate
    #   symbols libVoiceSdk needs (we missed this at iter 194 by checking
    #   only Interpreter symbols, not the full export set)
    # The libVoiceSdk cluster is restored via dodge's blob_fixup pattern
    # (FIX_SONAME libtensorflowlite_c.so + replace_needed in extract-files.py).
    'server_configurable_flags.so',             # session 1 line 64
    'libcamera_metadata.so',                    # session 1 line 12.
                                                 # system/media/camera/Android.bp
                                                 # vendor_available, NOT a camera
                                                 # HAL impl. (Also listed in
                                                 # SYSTEM_LIBS for unresolved
                                                 # warning suppression — those
                                                 # are independent.)

    # === Core AOSP system libs (also in SYSTEM_LIBS allowlist) ===
    # These are the bionic + libutils + libbinder + AIDL transport stack
    # that every vendor process links against. AOSP source builds them
    # with vendor_available: true (verified manually for each). The
    # stock dump captured them as vendor prebuilts but every device
    # uses the source-built versions; Soong PART says "system != vendor"
    # because we have both. Drop the prebuilts; the linker resolves to
    # /system/lib*/<basename> via the standard vendor namespace search
    # path. The SYSTEM_LIBS allowlist (used for closure.py unresolved-
    # NEEDED suppression) and EXCLUDE_BASENAMES (used for the
    # proprietary-files.txt emit step) are independent — both must
    # contain the basename for the right behavior.
    'libaudioutils.so',
    'libbase.so',
    'libbinder.so',
    'libc++.so',
    'libcrypto.so',
    'libcutils.so',
    'libfmq.so',
    'libhidlbase.so',
    'libhidlmemory.so',
    'libjsoncpp.so',
    'libprocessgroup.so',
    'libssl.so',
    'libunwindstack.so',
    'libutils.so',
    'libxml2.so',
    'libz.so',

    # === Packaging conflicts with AOSP source/toolchain prebuilts ===
    # soong_filesystem_creator detected two modules installing to the
    # same vendor partition path. The other source is the AOSP-side
    # prebuilt (toolchain or prebuilts/misc/), which is canonical.
    # Drop our vendor copies.
    # libclang_rt.ubsan_standalone-aarch64-android: clang UBSan runtime
    #   from prebuilts/clang/host/linux-x86/libclang_rt.ubsan_standalone.
    'libclang_rt.ubsan_standalone-aarch64-android.so',
    # libprotobuf-cpp-full/lite-21.12: vendor protobuf compat libs from
    #   prebuilts/misc/protobuf_vendorcompat/.
    'libprotobuf-cpp-full-21.12.so',
    'libprotobuf-cpp-lite-21.12.so',
    # libalsautils + libalsautilsv2: system/media/alsa_utils/Android.bp
    #   via libalsautils_defaults cc_defaults (vendor_available). ALSA
    #   helpers, NOT audio HAL impls. Both wrongly dropped in session 1
    #   (lines 5-6).
    'libalsautils.so',
    'libalsautilsv2.so',
    # libaudioserviceexampleimpl: hardware/interfaces/audio/aidl/default/
    #   Android.bp cc_library inheriting `vendor: true` from
    #   aidlaudioservice_defaults. Source links against latest audio.core
    #   (V4); our vendor prebuilt links V3 → multi-version conflict in
    #   libaudiocorehal.default's closure. Source path is in escape-hatch
    #   allow-list (hardware/interfaces/).
    'libaudioserviceexampleimpl.so',
    # libmemunreachable: system/memory/libmemunreachable/Android.bp
    #   (vendor_available). Memory leak detection helper.
    'libmemunreachable.so',
    # libcodec2_hidl@1.0: frameworks/av/media/codec2/hal/hidl/1.0/utils/
    #   Android.bp (vendor_available). HIDL codec2 helper.
    'libcodec2_hidl@1.0.so',
    # libblas: external/cblas/Android.bp (vendor_available). BLAS
    #   linear-algebra reference impl.
    'libblas.so',

    # === Qualcomm AIDL stable interfaces (vendor.qti.*-V*-ndk.so) ===
    # Per-version verified by verify_aidl_versions.py against source
    # aidl_interface { versions: [...] } blocks. Each entry below has:
    #   1. A located source Android.bp under vendor/qcom/opensource/ or
    #      hardware/qcom-caf/sm8750/.
    #   2. The dropped version present in versions: [...] or as the
    #      unfrozen current.
    #   3. Either explicit vendor_available: true OR stability: "vintf"
    #      (vintf-stable AIDL is inherently vendor-consumable).
    #
    # The 84 vendor.qti.* basenames in the dump WITHOUT a locatable
    # source aidl_interface block are KEPT — they're the canonical
    # implementation; dropping them removes a real interface with no
    # replacement.
    #
    # Multi-version captures (display.config V1..V13, composer3 V1..V4,
    # camera.aon V1..V3, etc.) are the stock dump's snapshot across
    # vendor build generations. Source has them all in versions_with_info,
    # so all are safe to drop.
    #
    # See device/nubia/NX809J/.aidl_verify.txt for the full DROP/KEEP/
    # UNKNOWN table.
    'vendor.qti.AvfQcvmManager-V1-ndk.so',
    'vendor.qti.hardware.agm-V1-ndk.so',
    'vendor.qti.hardware.bluetooth.audio-V1-ndk.so',
    'vendor.qti.hardware.camera.aon-V1-ndk.so',
    'vendor.qti.hardware.camera.aon-V2-ndk.so',
    'vendor.qti.hardware.camera.aon-V3-ndk.so',
    'vendor.qti.hardware.camera.offlinecamera-V1-ndk.so',
    'vendor.qti.hardware.camera.offlinecamera-V2-ndk.so',
    'vendor.qti.hardware.display.aiqe-V3-ndk.so',
    'vendor.qti.hardware.display.color-V1-ndk.so',
    'vendor.qti.hardware.display.composer3-V1-ndk.so',
    'vendor.qti.hardware.display.composer3-V2-ndk.so',
    'vendor.qti.hardware.display.composer3-V3-ndk.so',
    'vendor.qti.hardware.display.composer3-V4-ndk.so',
    'vendor.qti.hardware.display.config-V1-ndk.so',
    'vendor.qti.hardware.display.config-V2-ndk.so',
    'vendor.qti.hardware.display.config-V3-ndk.so',
    'vendor.qti.hardware.display.config-V4-ndk.so',
    'vendor.qti.hardware.display.config-V5-ndk.so',
    'vendor.qti.hardware.display.config-V6-ndk.so',
    'vendor.qti.hardware.display.config-V7-ndk.so',
    'vendor.qti.hardware.display.config-V8-ndk.so',
    'vendor.qti.hardware.display.config-V9-ndk.so',
    'vendor.qti.hardware.display.config-V10-ndk.so',
    'vendor.qti.hardware.display.config-V11-ndk.so',
    'vendor.qti.hardware.display.config-V12-ndk.so',
    'vendor.qti.hardware.display.config-V13-ndk.so',
    'vendor.qti.hardware.display.demura-V1-ndk.so',
    'vendor.qti.hardware.display.postproc-V1-ndk.so',
    'vendor.qti.hardware.pal-V1-ndk.so',
    'vendor.qti.hardware.paleventnotifier-V2-ndk.so',
    'vendor.qti.hardware.qspa-V1-ndk.so',
    'vendor.qti.hardware.servicetrackeraidl-V1-ndk.so',
    'vendor.qti.hardware.systemhelperaidl-V1-ndk.so',
    'vendor.qti.hardware.wifi.supplicant-V1-ndk.so',
    # wifi_legacy: hardware/interfaces/wifi/legacy_headers/Android.bp
    #   cc_library_shared vendor_available. Wifi legacy header impl,
    #   NOT a wifi HAL impl. Was wrongly dropped in session 1 (line 77).
    'wifi_legacy.so',

    # === Bulk kati duplicate-target collisions on vendor lib*/.so ===
    # Bulk-found by walking proprietary/vendor/lib{,64}/*.so and grepping
    # each basename across hardware/, frameworks/, external/, system/,
    # packages/, vendor/qcom/. Each entry has a source-side Android.bp
    # that defines the same module name and installs to the vendor
    # partition. The kati MODULE.TARGET.SHARED_LIBRARIES.<name> already
    # defined error IS the escape-hatch evidence (source installs at
    # the same vendor/lib64/<basename> path). Drop our copies; source
    # is canonical for all of these.
    'android.hardware.bluetooth.audio-impl.so',  # hardware/interfaces/bluetooth/audio/aidl/default/Android.bp
    # === iter 201: non-QTI bluetooth audio bridges (match dodge pattern) ===
    # Stock Nubia ships both AOSP-reference and QTI-customized BT audio HAL
    # variants. On Qualcomm hardware, only the QTI variants are actually used
    # at runtime — the non-QTI bridges are dead weight that Nubia ships but
    # the running device doesn't load.
    #
    # We already ship the QTI variants (kept in proprietary-files.txt):
    #   vendor/lib64/hw/android.hardware.bluetooth.audio-impl-qti.so
    #   vendor/lib64/hw/vendor.qti.hardware.bluetooth_audio@2.0-impl.so
    #   vendor/lib64/hw/vendor.qti.hardware.bluetooth_audio@2.1-impl.so
    #   vendor/lib64/libbluetooth_audio_session_aidl_qti.so
    #   vendor/lib64/libbluetooth_audio_session_qti.so
    #   vendor/lib64/libbluetooth_audio_session_qti_2_1.so
    #   vendor/lib64/hw/audio.bluetooth_qti.default.so
    #
    # Iter 201 halt was on audio.bluetooth.default.so → DT_NEEDED unresolved
    # IsSessionReady from libbluetooth_audio_session_aidl.so (which closure.py
    # excluded under iter-173 speculative pattern). Investigation revealed the
    # IsSessionReady symbol exists in 3 QTI variants we already ship. The
    # non-QTI bridges are dead weight; restoring their providers would be
    # wasted work.
    #
    # Dodge sm8750-common verified iter 201:
    # - Ships ONLY the QTI variants (and a FIX_SONAME-renamed copy of
    #   libbluetooth_audio_session_aidl.so → _prebuilt.so that doesn't satisfy
    #   normal DT_NEEDED lookups)
    # - Does NOT ship audio.bluetooth.default.so or @2.0-impl.so or related
    # - BT audio works on dodge exclusively via the QTI path
    #
    # 6th iter-173 pattern instance, but the right fix here is "drop dead
    # weight" rather than "restore symbols" because (unlike libtensorflowlite_jni
    # and libqti-perfd-client) the symbols exist in 3 QTI variants we already
    # ship. The pre-flash audit task should distinguish these two sub-patterns.
    'audio.bluetooth.default.so',  # hardware/interfaces/bluetooth/audio/aidl/default/Android.bp (non-QTI fallback)
    'android.hardware.bluetooth.audio@2.0-impl.so',  # hardware/interfaces/bluetooth/audio/2.0/default/Android.bp (HIDL legacy non-QTI)
    'libOpenCL.so',  # external/OpenCL-ICD-Loader/Android.bp
    'libagmipcservice.so',  # hardware/qcom-caf/sm8750/audio/agm/ipc/aidl/server/Android.bp
    'libaudioplatformconverter.qti.so',  # hardware/qcom-caf/sm8750/audio/primary-hal/hal/converter/Android.bp
    'libbluetooth_audio_session.so',  # hardware/interfaces/bluetooth/audio/utils/Android.bp
    'libbluetooth_audio_session_aidl.so',  # hardware/interfaces/bluetooth/audio/utils/Android.bp
    'libcodec2_hidl_plugin.so',  # frameworks/av/media/codec2/hal/plugin/Android.bp
    'libeffects.so',  # frameworks/av/media/libeffects/factory/Android.bp
    'libjson.so',  # external/json-c/Android.bp
    'libkeystore-engine-wifi-hidl.so',  # system/security/keystore-engine/Android.bp
    'libkeystore-wifi-hidl.so',  # system/security/keystore/Android.bp
    'libmapperutils.so',  # hardware/qcom-caf/sm8750/display/hal/gralloc/Android.bp
    'libnbaio_mono.so',  # frameworks/av/media/libnbaio/Android.bp
    'libnetfilter_conntrack.so',  # external/libnetfilter_conntrack/Android.bp
    'libnfnetlink.so',  # external/libnfnetlink/Android.bp
    'libpaleventnotifier.so',  # hardware/qcom-caf/sm8750/audio/pal/ipc/aidl/palnotifierserver/Android.bp
    'libpalipcservice.so',  # hardware/qcom-caf/sm8750/audio/pal/ipc/aidl/server/Android.bp
    'libpasn.so',  # external/wpa_supplicant_8/wpa_supplicant/Android.bp
    # NOTE: libqti-perfd-client.so was previously excluded under the speculative
    # rationale "hardware/qcom-caf/common/libqti-perfd-client/Android.bp" (i.e.
    # "source provides this"). Iter 200 verification caught the 5th iter-173
    # speculative drop:
    # - Nubia's libqti-perfd-client.so defines perf_get_prop_extn (and similar
    #   _extn extension symbols) at offset 0xe328
    # - Source-side libqti-perfd-client is a minimal stub (cc_library_shared,
    #   srcs: ["client.c"], shared_libs: liblog/libutils only) — does NOT export
    #   the _extn symbols Nubia consumers need
    # - 4 vendor consumers (libmemgen, vendor.qti.MemHal-service,
    #   vendor.qti.hardware.perf2-hal-service, qsap_qapeservice) DT_NEEDED this
    # - Source has vendor variant (vendor: true) so prefer:true cleanly resolves
    #   the collision (verified iter 200 — different from libtensorflowlite_jni
    #   case where source had no vendor variant and the rename+lib_fixups dance
    #   was required)
    # - Dodge sm8750-common ships this as a vanilla cc_prebuilt with no special
    #   handling (no rename, no FIX_SONAME, no lib_fixups) — confirms plain
    #   restore is sufficient for this specific case
    #
    # 5th iter-173 speculative-attribution drop caught at build time. The
    # pre-flash audit task is now MANDATORY and HIGHEST priority.
    'libqti_vndfwk_detect_vendor.so',  # vendor/qcom/opensource/core-utils-vendor/fwk-detect/Android.bp
    'libqtivibratoreffect.so',  # vendor/qcom/opensource/vibrator/effect/Android.bp
    'librmnetctl.so',  # vendor/qcom/opensource/dataservices/rmnetctl/Android.bp
    'libsdmclient.so',  # hardware/qcom-caf/sm8750/display/core/sdmclient/Android.bp
    'libsensorndkbridge.so',  # frameworks/hardware/interfaces/sensorservice/libsensorndkbridge/Android.bp
    'libsgutils2.so',  # external/sg3_utils/Android.bp
    'libskia.so',  # external/skia/Android.bp
    # NOTE: libtensorflowlite_jni.so was previously excluded under the speculative
    # rationale "source provides this" (closure.py iter ~1382). Iter 194 verification
    # caught the speculation:
    # - Source-built libtensorflowlite_jni has only an android_arm64_armv9-a_shared
    #   variant (system, no vendor variant — verified iter 194 Step 1)
    # - Source's symbols are unversioned; Nubia's vendor consumers require
    #   versioned @VERS_1.0 symbols
    # - 3 of 4 affected vendor consumers (libai_tflite, libimage_segment,
    #   libmfnr_network) DT_NEEDED libtensorflowlite_jni.so directly and require
    #   the versioned symbols
    # - Nubia's prebuilt libtensorflowlite_jni.so defines all 218 needed TfLite
    #   symbols including TfLiteInterpreterModifyGraphWithDelegate@@VERS_1.0
    # - No collision with source: source has no vendor variant, so restoring
    #   our prebuilt is clean
    #
    # libVoiceSdk.so is the 4th affected consumer but it DT_NEEDS libtensorflowlite_c.so
    # (NOT libtensorflowlite_jni). That's a separate problem investigated at the next
    # halt. This commit fixes only the 3 jni-dependent consumers.
    #
    # This is the FOURTH iter-173 speculative-attribution drop caught at build time
    # (after apex closure, VINTF compat matrix, erofs system filesystem). The
    # iter-173 audit pre-flash gate is now MANDATORY and HIGHEST priority.
    'libtinycompress.so',  # external/tinycompress/Android.bp
    'libvndfwk_detect_jni.qti_vendor.so',  # vendor/qcom/opensource/core-utils-vendor/fwk-detect/Android.bp
    'libwfdaac_vendor.so',  # vendor/qcom/opensource/commonsys/wfd/libaac/Android.bp
    'libwifi-hal.so',  # frameworks/opt/net/wifi/libwifi_hal/Android.bp
    # === qcwcn namespace ownership (iter 190) ===
    # Importing hardware/qcom-caf/wlan/qcwcn as a Soong namespace (iter 189)
    # is an architectural commitment that source canonically owns everything
    # in qcwcn. Soong's prefer: true mechanism doesn't reliably handle static
    # variant collisions across imported namespaces (only shared variants).
    # Same pattern as the AIDL stable interface drops (iters 53-89): when
    # source canonically provides a thing, drop our prebuilt.
    #
    # Verified iter 190: dodge sm8750-common does NOT ship libwifi-hal-* as
    # prebuilts (proprietary-files.txt cross-reference, both local cache and
    # TheMuppets remote). This is the standard LineageOS-on-SM8750 pattern.
    #
    # Preemptive scan: only 2 collisions exist between qcwcn modules and our
    # vendor prebuilts (libwifi-hal-ctrl + libwifi-hal-qcom). Bulk-fixed here
    # to close the wifi cascade in one edit instead of iterating one halt at
    # a time.
    'libwifi-hal-ctrl.so',  # hardware/qcom-caf/wlan/qcwcn/Android.bp (cc_library_shared, vendor: true)
    'libwifi-hal-qcom.so',  # hardware/qcom-caf/wlan/qcwcn/Android.bp (cc_library, vendor: true)
    'libwpa_client.so',  # external/wpa_supplicant_8/wpa_supplicant/Android.bp
    'libstd.dylib.so',   # prebuilts/rust/Android.bp — Rust std dynlib variant
    'vendor.qti.hardware.display.snapalloc-impl.so',  # hardware/qcom-caf/sm8750/display/core/snapalloc/Android.bp
    'vendor.qti.hardware.vibrator.impl.so',  # vendor/qcom/opensource/vibrator/aidl/Android.bp
    'vendor.qti.hardware.vibratorCL.impl.so',  # vendor/qcom/opensource/vibrator/aidl/VibratorCL/Android.bp
    'vendor.qti.hardware.vibratorOL.impl.so',  # vendor/qcom/opensource/vibrator/aidl/VibratorOL/Android.bp
    'vendor.qti.hardware.vibratorSel.impl.so',  # vendor/qcom/opensource/vibrator/aidl/VibratorSelector/Android.bp
}


def enumerate_dump():
    """Walk DUMP_ROOT, return list of relative paths sorted.

    The dump may contain BARE top-level directories like proprietary/etc/,
    proprietary/lib64/, proprietary/dsp/ that came from a different mount
    than /vendor — these are dropped because:
      1. extract-utils requires every entry to start with a known partition
         prefix (vendor/, system/, system_ext/, product/, odm/, recovery/);
      2. spot-checked: every interesting bare-prefix file already has a
         counterpart under proprietary/vendor/<same-relative-path> (e.g.
         libEGL_adreno.so exists in BOTH proprietary/lib64/egl/ and
         proprietary/vendor/lib64/egl/, with different MD5s — the
         vendor-partition copy is the one the device actually loads at
         runtime via the /vendor mount);
      3. LineageOS provides its own /system partition, so even if the
         bare-prefix files are genuine /system blobs they're not needed.
    Skipped files are reported in the summary so we can revisit.
    """
    files = []
    skipped_top = defaultdict(int)
    skipped_symlinks = 0
    skipped_apk_libs = 0
    for root, dirs, fs in os.walk(DUMP_ROOT, followlinks=False):
        dirs[:] = [d for d in dirs if not d.startswith('.')]
        for f in fs:
            if f.startswith('.'):
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, DUMP_ROOT)
            parts = rel.split('/')
            top = parts[0]
            if top not in PARTITION_TOPLEVELS:
                skipped_top[top] += 1
                continue
            if len(parts) >= 2 and parts[1] in UNHANDLED_SUBDIRS:
                skipped_top[f'{top}/{parts[1]}'] += 1
                continue
            # Skip symlinks: extract-utils generates a duplicate
            # cc_prebuilt_library_shared module for each symlink, which
            # conflicts with the target file's module. Symlinks should
            # be created at install time via Android.mk or device.mk
            # rules, not via proprietary-files.txt entries.
            if os.path.islink(full):
                skipped_symlinks += 1
                continue
            # Skip libs bundled inside APK install directories
            # (vendor/app/Foo/lib/arm64/libbar.so). These belong to the
            # APK's native lib dir; extract-utils packages the APK and
            # picks up its bundled libs automatically. Listing them as
            # standalone prebuilts collides with the same-basename
            # standalone libs in vendor/lib64/.
            if (top in ('vendor', 'system', 'system_ext', 'product', 'odm') and
                len(parts) >= 5 and parts[1] in ('app', 'priv-app') and
                parts[3] == 'lib'):
                skipped_apk_libs += 1
                continue
            if os.path.basename(rel) in EXCLUDE_BASENAMES:
                skipped_top[f'EXCLUDE:{os.path.basename(rel)}'] += 1
                continue
            if rel in EXCLUDE_PATHS:
                skipped_top[f'EXCLUDE_PATH:{rel}'] += 1
                continue
            if any(rel.startswith(p) for p in RUNTIME_PARTITION_PATHS):
                skipped_top[f'RUNTIME_PART:{rel.split("/", 2)[1] if rel.count("/") >= 1 else rel}'] += 1
                continue
            if any(rel.startswith(p) for p in PCF_BYPASS_PATHS):
                skipped_top[f'PCF_BYPASS:{rel.split("/", 2)[1] if rel.count("/") >= 1 else rel}'] += 1
                continue
            files.append(rel)
    files.sort()
    if skipped_top:
        print('Bare-prefix files skipped (no recognized partition):')
        for t in sorted(skipped_top):
            print(f'  {t}/  — {skipped_top[t]} files')
    if skipped_symlinks:
        print(f'Symlinks skipped: {skipped_symlinks}')
    if skipped_apk_libs:
        print(f'APK-bundled libs skipped: {skipped_apk_libs}')

    return files


def read_dt_needed(path):
    """Run readelf -d, return list of NEEDED basenames. Empty if not ELF."""
    try:
        out = subprocess.run(
            ['readelf', '-d', path],
            capture_output=True, text=True, timeout=10,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return []
    if out.returncode != 0:
        return []
    needed = []
    for line in out.stdout.splitlines():
        # 0x... (NEEDED)  Shared library: [libfoo.so]
        m = re.search(r'\(NEEDED\).*\[([^\]]+)\]', line)
        if m:
            needed.append(m.group(1))
    return needed


def load_existing_prefixes(path):
    """Parse existing proprietary-files.txt for blob_path -> prefix marker."""
    prefixes = {}
    if not os.path.exists(path):
        return prefixes
    for line in open(path):
        line = line.rstrip('\n')
        if not line or line.startswith('#'):
            continue
        s = line.lstrip()
        prefix = ''
        if s.startswith('-') or s.startswith(';'):
            prefix = s[0]
            s = s[1:].lstrip()
        # strip "|hash" or " | hash" suffix if present (some files have sha1 pin)
        blob = s.split('|', 1)[0].strip()
        if blob:
            prefixes[blob] = prefix
    return prefixes


def section_for(rel_path):
    """Pick a section header for a relative path."""
    # rel_path is like 'vendor/lib64/foo.so' or 'vendor/etc/init/...'
    parts = rel_path.split('/')
    # Functional grouping by subdirectory
    if len(parts) >= 3 and parts[0] == 'vendor':
        sub = parts[1]
        sub2 = parts[2] if len(parts) > 3 else ''
        if sub == 'firmware' or sub == 'firmware_mnt' or sub == 'rfs' or sub == 'dsp' or sub == 'bt_firmware' or sub == 'soccp_firmware':
            return f'vendor/{sub}'
        if sub == 'etc':
            return f'vendor/etc/{sub2}' if sub2 else 'vendor/etc'
        if sub in ('lib', 'lib64'):
            sub2 = parts[2] if len(parts) > 3 else ''
            if sub2 == 'hw':
                return f'vendor/{sub}/hw'
            if sub2 in ('camera', 'soundfx', 'mediadrm', 'egl', 'drm', 'rfsa', 'vndk-sp', 'mediacas'):
                return f'vendor/{sub}/{sub2}'
            return f'vendor/{sub}'
        if sub == 'bin':
            sub2 = parts[2] if len(parts) > 3 else ''
            if sub2 == 'hw':
                return 'vendor/bin/hw'
            return 'vendor/bin'
        if sub == 'app' or sub == 'priv-app':
            return f'vendor/{sub}'
        if sub == 'overlay':
            return 'vendor/overlay'
        return f'vendor/{sub}'
    if parts[0] == 'etc':
        return f'etc/{parts[1]}' if len(parts) > 1 else 'etc'
    if parts[0] == 'odm':
        return f'odm/{parts[1]}' if len(parts) > 1 else 'odm'
    if parts[0] == 'system':
        return f'system/{parts[1]}' if len(parts) > 1 else 'system'
    return parts[0]


def compute_module_renames(rel_paths):
    """Detect basename collisions where one copy lives at the canonical
    vendor/lib*/<basename> path and another lives in a deeper subdir like
    vendor/lib*/hw/<subdir>/<basename>. extract-utils generates module
    names from the basename, so these collide. Disambiguate by appending
    ';MODULE=<basename>_<subdir>' to the deeper-path entry. Returns a
    dict mapping rel_path -> module-rename-suffix string ('' if none).
    """
    # Find all vendor/lib*/<basename> canonical entries
    canonical = {}  # basename -> rel
    for rel in rel_paths:
        parts = rel.split('/')
        if len(parts) == 3 and parts[0] == 'vendor' and parts[1].startswith('lib'):
            canonical[parts[2]] = rel
    renames = {}
    for rel in rel_paths:
        bn = os.path.basename(rel)
        if bn not in canonical:
            continue
        if rel == canonical[bn]:
            continue
        # Same basename as a canonical entry, different path → rename
        # Suffix derived from the parent subdir (e.g., 'hw/audio' -> 'hw_audio')
        parts = rel.split('/')
        # Take everything between 'vendor/lib*/' and the basename
        if len(parts) >= 4 and parts[0] == 'vendor' and parts[1].startswith('lib'):
            subdir_parts = parts[2:-1]
        else:
            subdir_parts = parts[1:-1]
        suffix = '_' + '_'.join(subdir_parts).replace('-', '_').replace('.', '_')
        root, ext = os.path.splitext(bn)
        # Strip trailing numeric extension (e.g., .so.1 -> .so)
        while True:
            r2, e2 = os.path.splitext(root)
            if e2 and e2[1:].isdigit():
                root = r2
            else:
                break
        renames[rel] = root + suffix
    return renames


def emit_proprietary_files(rel_paths, prefixes, out_path):
    """Write rel_paths (sorted) into out_path, sectioned and prefixed."""
    renames = compute_module_renames(rel_paths)
    # Merge manual renames (e.g., to sidestep kati duplicate-target collisions
    # between vendor prebuilts and same-named source-built modules).
    renames.update(MANUAL_MODULE_RENAMES)
    if renames:
        print(f'Module renames (basename collision resolution): {len(renames)}')
        for r, m in list(renames.items())[:5]:
            print(f'  {r}  ;MODULE={m}')
        if len(renames) > 5:
            print(f'  ... and {len(renames)-5} more')
    # Group by section
    groups = defaultdict(list)
    for rel in rel_paths:
        groups[section_for(rel)].append(rel)
    # Determine section ordering: vendor/etc first, then lib/lib64, then bin, then firmware, then misc
    def section_sort_key(s):
        order = [
            'vendor/etc', 'vendor/etc/init', 'vendor/etc/vintf',
            'vendor/etc/permissions', 'vendor/etc/audio', 'vendor/etc/seccomp_policy',
            'vendor/lib', 'vendor/lib/hw', 'vendor/lib/egl', 'vendor/lib/drm',
            'vendor/lib/soundfx', 'vendor/lib/mediadrm', 'vendor/lib/vndk-sp',
            'vendor/lib64', 'vendor/lib64/hw', 'vendor/lib64/camera',
            'vendor/lib64/soundfx', 'vendor/lib64/drm', 'vendor/lib64/mediadrm',
            'vendor/lib64/mediacas', 'vendor/lib64/vndk-sp', 'vendor/lib64/rfsa',
            'vendor/bin', 'vendor/bin/hw',
            'vendor/firmware', 'vendor/firmware_mnt', 'vendor/dsp', 'vendor/rfs',
            'vendor/bt_firmware', 'vendor/soccp_firmware',
            'vendor/app', 'vendor/priv-app', 'vendor/overlay',
            'vendor/qprof', 'vendor/odm_dlkm',
        ]
        try:
            return (0, order.index(s), s)
        except ValueError:
            return (1, 0, s)
    sections_sorted = sorted(groups.keys(), key=section_sort_key)

    with open(out_path, 'w') as f:
        f.write('# Proprietary blobs for Nubia Red Magic 11 Pro (NX809J)\n')
        f.write('# Qualcomm Snapdragon 8 Elite (SM8850 / canoe)\n')
        f.write('# AUTO-GENERATED by device/nubia/NX809J/closure.py — re-run after dump changes.\n')
        f.write('# Inclusion gate: every regular file under vendor/nubia/NX809J/proprietary/.\n')
        f.write('\n')
        for sect in sections_sorted:
            f.write(f'# {sect}\n')
            for rel in sorted(groups[sect]):
                pref = prefixes.get(rel, '')
                line = f'{pref}{rel}'
                if rel in renames:
                    line += f';MODULE={renames[rel]}'
                if rel in DISABLE_DEPS_PATHS:
                    line += ';DISABLE_DEPS'
                if rel in OVERRIDES_PATHS:
                    line += f';OVERRIDES={OVERRIDES_PATHS[rel]}'
                if rel in FIX_SONAME_PATHS:
                    # Format: src:dst;FIX_SONAME — extract-utils renames the
                    # binary's SONAME and installs at the dst path
                    line = f'{pref}{rel}:{FIX_SONAME_PATHS[rel]};FIX_SONAME'
                f.write(line + '\n')
            f.write('\n')


def emit_pcf_bypass_mk(out_path):
    """Emit firmware_pcf.mk with one PRODUCT_COPY_FILES entry per file
    under any PCF_BYPASS_PATHS prefix. Idempotent."""
    bypass_files = []
    for root, dirs, fs in os.walk(DUMP_ROOT):
        dirs[:] = [d for d in dirs if not d.startswith('.')]
        for f in fs:
            if f.startswith('.'):
                continue
            full = os.path.join(root, f)
            if os.path.islink(full):
                continue
            rel = os.path.relpath(full, DUMP_ROOT)
            if any(rel.startswith(p) for p in PCF_BYPASS_PATHS):
                bypass_files.append(rel)
    bypass_files.sort()
    with open(out_path, 'w') as f:
        f.write('# AUTO-GENERATED by device/nubia/NX809J/closure.py.\n')
        f.write('# Do not edit by hand — re-run closure.py to regenerate.\n')
        f.write('#\n')
        f.write('# PRODUCT_COPY_FILES rules for files under PCF_BYPASS_PATHS\n')
        f.write('# prefixes in closure.py. These bypass extract-utils + Soong\n')
        f.write('# entirely (Make-level install). See closure.py for the\n')
        f.write('# per-prefix rationale.\n')
        f.write('\n')
        # Group by top-level subdir for readability
        from collections import OrderedDict
        groups = OrderedDict()
        for rel in bypass_files:
            top = '/'.join(rel.split('/')[:2])  # e.g., vendor/firmware_mnt
            groups.setdefault(top, []).append(rel)
        for top, files_in_group in groups.items():
            f.write(f'# {top}/ — {len(files_in_group)} files\n')
            f.write('PRODUCT_COPY_FILES += \\\n')
            for i, rel in enumerate(files_in_group):
                src = f'vendor/nubia/NX809J/proprietary/{rel}'
                dst = f'$(TARGET_COPY_OUT_VENDOR)/{rel[len("vendor/"):]}' if rel.startswith('vendor/') else f'$(TARGET_COPY_OUT_VENDOR)/{rel}'
                trailing = ' \\' if i < len(files_in_group) - 1 else ''
                f.write(f'    {src}:{dst}{trailing}\n')
            f.write('\n')
    return len(bypass_files)


def main():
    if not os.path.isdir(DUMP_ROOT):
        print(f'ERROR: dump root not found: {DUMP_ROOT}', file=sys.stderr)
        sys.exit(1)

    print(f'Dump root: {DUMP_ROOT}')
    pcf_path = os.path.join(HERE, 'firmware_pcf.mk')
    pcf_count = emit_pcf_bypass_mk(pcf_path)
    print(f'PCF bypass files emitted to firmware_pcf.mk: {pcf_count}')
    rel_paths = enumerate_dump()
    print(f'Total files in dump: {len(rel_paths)}')

    # Load prefixes from existing file
    prefixes = load_existing_prefixes(EXISTING_TXT)
    print(f'Existing prefix markers preserved: {sum(1 for v in prefixes.values() if v)}')

    # Build basename index for resolution check
    basename_index = defaultdict(list)
    for rel in rel_paths:
        basename_index[os.path.basename(rel)].append(rel)

    # DT_NEEDED walk — diagnostics only
    unresolved = []  # list of (parent_rel, needed)
    elf_count = 0
    for rel in rel_paths:
        full = os.path.join(DUMP_ROOT, rel)
        if not is_likely_elf(full):
            continue
        if not os.path.isfile(full) or os.path.islink(full):
            continue
        needed = read_dt_needed(full)
        if not needed:
            continue
        elf_count += 1
        for n in needed:
            if n in SYSTEM_LIBS:
                continue
            if n in basename_index:
                continue
            if n == 'libgcc.so' and is_dsp_parent(rel):
                continue
            # AIDL -ndk libs we intentionally excluded resolve at runtime
            # via the documented vendor->system AIDL linker namespace
            # exemption. Don't warn on them — they're not orphans, they
            # just live on the system partition built from source.
            if n in EXCLUDE_BASENAMES:
                continue
            unresolved.append((rel, n))

    # Sort unresolved by needed-count desc
    needed_count = defaultdict(int)
    for parent, n in unresolved:
        needed_count[n] += 1
    top20 = sorted(needed_count.items(), key=lambda kv: -kv[1])[:20]

    with open(UNRESOLVED_OUT, 'w') as f:
        f.write('# Unresolved DT_NEEDED entries (parent -> missing)\n')
        f.write(f'# Total ELF walked: {elf_count}\n')
        f.write(f'# Total unresolved edges: {len(unresolved)}\n')
        f.write(f'# Distinct missing basenames: {len(needed_count)}\n')
        f.write('\n')
        f.write('## Top distinct missing basenames (count, name):\n')
        for n, c in sorted(needed_count.items(), key=lambda kv: -kv[1]):
            f.write(f'{c:6d}  {n}\n')
        f.write('\n## Full edge list (parent -> needed):\n')
        for parent, n in sorted(unresolved):
            f.write(f'{parent} -> {n}\n')

    # Baseline diff
    baseline_missing = []
    if os.path.exists(BASELINE_TXT):
        baseline_basenames = set()
        for line in open(BASELINE_TXT):
            s = line.strip()
            if s:
                baseline_basenames.add(s)
        for b in sorted(baseline_basenames):
            if b not in basename_index:
                baseline_missing.append(b)
        with open(BASELINE_DIFF_OUT, 'w') as f:
            f.write('# Files listed in .bringup_orig_blobs.txt but NOT present in dump\n')
            f.write(f'# Baseline entries: {len(baseline_basenames)}\n')
            f.write(f'# Missing from dump: {len(baseline_missing)}\n')
            f.write('\n')
            for b in baseline_missing:
                f.write(b + '\n')

    # Emit proprietary-files.txt
    emit_proprietary_files(rel_paths, prefixes, PROP_TXT)

    # Summary
    print()
    print('=== SUMMARY ===')
    print(f'Files emitted to proprietary-files.txt: {len(rel_paths)}')
    print(f'ELF files walked for DT_NEEDED:        {elf_count}')
    print(f'Unresolved NEEDED edges:               {len(unresolved)}')
    print(f'Distinct missing NEEDED basenames:     {len(needed_count)}')
    if os.path.exists(BASELINE_TXT):
        print(f'Baseline blobs missing from dump:      {len(baseline_missing)}')
    print()
    print('Top 20 unresolved NEEDED basenames (count name):')
    for n, c in top20:
        print(f'  {c:5d}  {n}')
    print()
    print(f'Detail files written:')
    print(f'  {UNRESOLVED_OUT}')
    if os.path.exists(BASELINE_TXT):
        print(f'  {BASELINE_DIFF_OUT}')


if __name__ == '__main__':
    main()

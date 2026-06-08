#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/nubia/NX809J',
    'hardware/qcom-caf/sm8750',
    'hardware/qcom-caf/wlan',
    'vendor/qcom/opensource/commonsys-intf/display',
    # Namespaces required to resolve source-built modules whose
    # vendor prebuilt counterparts are now in EXCLUDE_BASENAMES
    # (per Rule 2 escape hatch). Without these, the source modules
    # exist but vendor/nubia/NX809J namespace can't read them.
    'external/OpenCL-ICD-Loader',
    'hardware/qcom-caf/common/libqti-perfd-client',
    'vendor/qcom/opensource/dataservices',
    # iter 191: vendor binaries cnss_diag (uses libwifi-hal-ctrl) +
    # hal_proxy_daemon, sigma_dut (use libwifi-hal-qcom) need to read
    # source-side modules now in qcwcn after iter 190 dropped our
    # libwifi-hal-{ctrl,qcom} prebuilts. The PRODUCT_SOONG_NAMESPACES
    # addition at iter 189 handled the global visibility layer; this
    # entry handles the per-namespace import layer for our vendor blob
    # consumers. dodge doesn't need this because dodge doesn't extract
    # cnss_diag/hal_proxy_daemon/sigma_dut as prebuilts at all
    # (verified iter 191 against TheMuppets remote + local cache).
    'hardware/qcom-caf/wlan/qcwcn',
]

def lib_fixup_libtensorflowlite_jni_nubia_vendor(lib: str, partition: str, *args, **kwargs):
    """iter 199: redirect vendor consumers' DT_NEEDED references from
    libtensorflowlite_jni to libtensorflowlite_jni_nubia_vendor.

    Iter 198 renamed our libtensorflowlite_jni prebuilt to
    libtensorflowlite_jni_nubia_vendor (via closure.py MANUAL_MODULE_RENAMES)
    to sidestep the kati duplicate-target collision against the source-built
    cc_library_shared in external/tensorflow. The rename succeeded at the
    Soong→Make layer but broke consumer resolution: vendor blobs (libai_tflite,
    libimage_segment, libmfnr_network) have DT_NEEDED libtensorflowlite_jni.so
    which extract-utils translates to shared_libs: ["libtensorflowlite_jni"]
    in their auto-generated cc_prebuilt_library_shared entries. After the
    rename, that name no longer matches our prebuilt and Soong falls back to
    the source-built version which lacks @VERS_1.0 symbols.

    This fixup completes Shape M (rename + lib_fixups together) by rewriting
    the consumer references to point at the renamed prebuilt. Only applies
    for vendor partition consumers — system consumers (if any) keep their
    references to the source-built libtensorflowlite_jni.

    The runtime install filename remains libtensorflowlite_jni.so (extract-utils
    stem: directive preserved it), so vendor consumers' DT_NEEDED at runtime
    still resolves to /vendor/lib64/libtensorflowlite_jni.so via the renamed
    prebuilt's installation.
    """
    return 'libtensorflowlite_jni_nubia_vendor' if partition == 'vendor' else None


lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    ('libtensorflowlite_jni',): lib_fixup_libtensorflowlite_jni_nubia_vendor,
}

blob_fixups: blob_fixups_user_type = {
    # libspukeymint and friends were built against android.hardware.security.
    # sharedsecret-V2-ndk, but AOSP source has the interface frozen at V1
    # (`versions: ["1"], frozen: true` in hardware/interfaces/security/
    # sharedsecret/aidl/Android.bp). Soong refuses V2 references with
    # "unfrozen development version ... explicitly marked as frozen: true".
    # The V2 .so itself is preserved via PRODUCT_COPY_FILES (device.mk),
    # so libspukeymint can dlopen it at runtime; we just need to scrub
    # the DT_NEEDED so extract-utils doesn't emit a Soong-side reference
    # at all. patchelf --remove-needed strips the entry from the binary
    # before extract-utils reads its dynamic section.
    'vendor/lib64/libspukeymint.so': blob_fixup()
        .remove_needed('android.hardware.security.sharedsecret-V2-ndk.so'),
    'vendor/lib64/libspukeymintdeviceutils.so': blob_fixup()
        .remove_needed('android.hardware.security.sharedsecret-V2-ndk.so'),
    'vendor/lib64/libspukeymintprovision.so': blob_fixup()
        .remove_needed('android.hardware.security.sharedsecret-V2-ndk.so'),
    'vendor/lib64/libspukeymintutils.so': blob_fixup()
        .remove_needed('android.hardware.security.sharedsecret-V2-ndk.so'),
    # iter 204: redirect libVoiceSdk cluster's DT_NEEDED to Nubia's renamed
    # libtensorflowlite_c_vendor.so. Mirrors dodge sm8750-common's blob_fixup
    # pattern (verified iter 204 against dodge's extract-files.py).
    #
    # Background: Nubia's libtensorflowlite_c.so exports XNNPACK delegate
    # symbols (TfLiteXNNPackDelegateCreate/Delete/OptionsDefault@@VERS_1.0)
    # that source-built libtensorflowlite_c.so does not. The libVoiceSdk
    # chain (libVoiceSdk + libcapiv2uvvendor + liblistensoundmodel2vendor)
    # DT_NEEDS libtensorflowlite_c.so for these symbols.
    #
    # Mechanism: closure.py FIX_SONAME_PATHS renames Nubia's libtensorflowlite_c.so
    # to libtensorflowlite_c_vendor.so (avoiding collision with source-built
    # version). This blob_fixup patches the consumer DT_NEEDED entries to point
    # at the renamed file. Both source-built and renamed vendor versions
    # coexist; the consumers use the renamed vendor version.
    #
    # iter 194 verification gap caught at iter 203-204: declared Nubia's
    # libtensorflowlite_c.so a "stub" based on checking only TfLiteInterpreter*
    # symbols (zero matches). Should have checked the FULL exported symbol set.
    # Process learning codified for the iter-173 audit.
    (
        'vendor/lib64/libVoiceSdk.so',
        'vendor/lib64/libcapiv2uvvendor.so',
        'vendor/lib64/liblistensoundmodel2vendor.so',
    ): blob_fixup()
        .replace_needed('libtensorflowlite_c.so', 'libtensorflowlite_c_vendor.so'),
}

module = ExtractUtilsModule(
    'NX809J',
    'nubia',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
)
# proprietary-files.txt is automatically added by the constructor

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()

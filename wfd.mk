# Copyright (C) 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# --- Wi-Fi Display / Miracast source (orphan-audit fix 2026-09-07) ---
# The vendor half already runs on our vendor image (wfdvndservice, wfdhdcphalservice,
# vendor.qti.hardware.wifidisplaysession_aidl in the VINTF manifest). The system half was
# missing entirely, and AOSP's own path is dead: RemoteDisplay.listen() ->
# IMediaPlayerService::listenForRemoteDisplay is "no longer supported". Stock (and our
# EvoX frameworks/base, which carries the CAF patch) instead routes WifiDisplayController
# through com.qualcomm.wfd.ExtendedRemoteDisplay, which ExtendedRemoteDisplayHelper loads
# from /system_ext/framework/WfdCommon.jar when it is not on the boot classpath. That class
# binds com.qualcomm.wfd.service.WfdService (priv-app, platform-signed here) which drives
# /system_ext/bin/wfdservice64 (binder "wifidisplaysession"...) + libwfd*.so, started via
# the vendor.wfdservice64=enable property from wfdservice.rc.
# So the user-facing UI is plain AOSP: Settings -> Connected devices -> Cast, and the
# Cast QS tile. No custom client is needed.
#
# All blobs are stock .18 /system_ext (Qualcomm-generic, not ZTE code) except four
# stock /system helper libs our A17 system lacks (libheif, libhwbinder/libhidltransport
# stubs, audio.common.types-V4-cpp). Two ABI seams are shimmed in frameworks/native
# (nx809j-gpp branch): SurfaceComposerClient::createVirtualDisplay 5-arg overload for
# libwfdmmsrc_system.so and MotionEvent::initialize(int flags) for libwfdnative.so.
# sepolicy (vendor_wfdservice, vendor_wfd_app, file/seapp/property contexts) is already
# compiled from device/qcom/sepolicy/generic. Notes: ~/lineage-scratch/NX809J_ORPHAN_AUDIT_0907.md

WFD_PATH := $(LOCAL_PATH)/wfd
WFD_SE := $(TARGET_COPY_OUT_SYSTEM_EXT)

PRODUCT_PACKAGES += \
    WfdService

# Three of the stock helper libs are built from source in our tree and soong owns their
# system_ext install path (kati "overriding commands" otherwise): request the modules instead.
# (stock copies kept in wfd/dup_sourcebuilt/ for reference; NOT installed)
PRODUCT_PACKAGES += \
    android.hidl.base@1.0 \
    vendor.display.config@2.0 \
    vendor.qti.hardware.display.config-V5-ndk

PRODUCT_COPY_FILES += \
    $(WFD_PATH)/bin/wfdservice64:$(WFD_SE)/bin/wfdservice64 \
    $(WFD_PATH)/etc/wfdservice.rc:$(WFD_SE)/etc/init/wfdservice.rc \
    $(WFD_PATH)/etc/wfdservice64.policy:$(WFD_SE)/etc/seccomp_policy/wfdservice64.policy \
    $(WFD_PATH)/etc/wfdconfigsink.xml:$(WFD_SE)/etc/wfdconfigsink.xml \
    $(WFD_PATH)/etc/wfd-system-ext-privapp-permissions-qti.xml:$(WFD_SE)/etc/permissions/wfd-system-ext-privapp-permissions-qti.xml \
    $(WFD_PATH)/framework/WfdCommon.jar:$(WFD_SE)/framework/WfdCommon.jar

PRODUCT_COPY_FILES += $(foreach f,$(notdir $(wildcard $(WFD_PATH)/lib64/*.so)), \
    $(WFD_PATH)/lib64/$(f):$(WFD_SE)/lib64/$(f))

# DisplayManagerService registers the WifiDisplayAdapter when this is set (stock sets it too);
# it is what makes the Cast settings page list Wi-Fi Display sinks.
PRODUCT_SYSTEM_PROPERTIES += \
    persist.debug.wfd.enable=1 \
    persist.sys.wfd.virtual=0

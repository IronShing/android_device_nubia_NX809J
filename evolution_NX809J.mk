#
# Copyright (C) 2025 Evolution X (adapted from LineageOS device config)
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
# iter 217: added full_base_telephony.mk — pulls in the ENTIRE Android framework
# (framework.jar, services.jar, SystemUI, APEXes, core apps, telephony stack).
# Without it, system.img was only 27 MB with no framework content = bootloop.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from NX809J device
$(call inherit-product, device/nubia/NX809J/device.mk)

# GApps variant selector:
#   true  = mini/basic (Pixel Launcher + Play Store + full GmsCore/Play Integrity, no Pixel bloat)
#   false = full Pixel GApps
# ── GApps variant ─────────────────────────────────────────────────────────────
# We ship BOTH variants; select per build with the NX809J_MINIMAL env/var:
# NAMING TRAP: neither variant is de-Googled. BOTH ship Play Store + GMS Core + GSF.
#   NX809J_MINIMAL=true  (default) → MinimalGApps: AOSP apps + Trebuchet, plus ONLY
#        Play Store + GMS Core + GSF (enough for Play Integrity). WITH_GMS:=false below
#        stops common_full_phone adding the Google-app TIER -- it does NOT mean "no
#        Google"; gms_core.mk adds the core back.
#   NX809J_MINIMAL=false           → FullGApps: full Pixel GApps (gms_full) incl. Google
#        Dialer.
# The old wording here said "de-Googled", which is wrong and has already misled a reader
# into concluding a flashed build had no GApps when it had all three packages.
NX809J_MINIMAL ?= true

# PDF viewer: GrapheneOS PdfViewer (device/nubia/NX809J/pdfviewer) instead of Lineage Camelot,
# which does not open PDFs on this device. The guard is honoured in vendor/lineage
# config/common_mobile_full.mk.
TARGET_EXCLUDES_CAMELOT := true
PRODUCT_PACKAGES += PdfViewer

# ── microG variant (third option, takes precedence over NX809J_MINIMAL) ───────
# NX809J_MICROG=true → de-Googled base + microG (Services + Companion + GsfProxy) and NO GApps.
# Kept as a separate flag rather than a third value of NX809J_MINIMAL so the existing two
# variants and their build scripts are untouched.
NX809J_MICROG ?= false
ifeq ($(NX809J_MICROG),true)
WITH_GMS := false
TARGET_USES_MINI_GAPPS := false
EVO_VERSION_SUFFIX := microG
endif

ifeq ($(NX809J_MICROG),false)
ifeq ($(NX809J_MINIMAL),true)
# Suppress only the Google-app TIER from the base. This is NOT "de-Googled" -- gms_core.mk
# below adds Play Store + GMS Core + GSF.
WITH_GMS := false
TARGET_USES_MINI_GAPPS := false
# Say what this build actually is. WITH_GMS=false above only suppresses the Google-app TIER;
# gms_core.mk below still adds Play Store + GMS Core + GSF, so the upstream default of "-Vanilla"
# (which means no Google at all) would be actively misleading to anyone choosing a build by name.
# NOTE: this only works because vendor/lineage/config/version.mk was patched to read
# EVO_VERSION_SUFFIX. Upstream ignores it, and builds up to 20260827 shipped as
# "12.1-Vanilla" despite carrying GApps. If vendor/lineage is ever re-synced clean, check
# ro.modversion on the result before publishing.
EVO_VERSION_SUFFIX := MinimalGApps
else
# full Pixel GApps tier
TARGET_USES_MINI_GAPPS := false
EVO_VERSION_SUFFIX := FullGApps
endif
endif

# Inherit common LineageOS configuration (brings the AOSP app suite + Trebuchet)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

ifeq ($(NX809J_MICROG),true)
# microG replaces GmsCore/GSF/Play Store entirely.
$(call inherit-product, device/nubia/NX809J/microg.mk)
PRODUCT_PACKAGES += \
    Launcher3QuickStep \
    webview \
    Contacts \
    messaging \
    Etar \
    DeskClock \
    Gallery2 \
    LatinIME \
    Dialer
endif

ifeq ($(NX809J_MINIMAL)$(NX809J_MICROG),truefalse)
# Core GApps only (Play Store + GMS Core + GSF) — everything else stays AOSP.
$(call inherit-product, device/nubia/NX809J/gms_core.mk)
# Guarantee the AOSP replacements are present (idempotent if the base already adds them).
PRODUCT_PACKAGES += \
    Launcher3QuickStep \
    webview \
    Contacts \
    messaging \
    Etar \
    DeskClock \
    Gallery2 \
    LatinIME
endif

# Power-off alarm (orphan-audit fix 2026-09-07). The vendor side already exists and works
# (vendor.qti.hardware.alarm.IAlarm/default + /vendor/bin/power_off_alarm charger watcher); what
# was missing is the system client that turns DeskClock's
# org.codeaurora.poweroffalarm.action.SET_ALARM broadcast into an RTC wake-up. No LOS source repo
# exists for it (LOS trees ship a proprietary PowerOffAlarm.apk), so device/nubia/NX809J/PowerOffAlarm
# is our own from-source implementation of the same package/protocol. seapp_contexts already maps
# com.qualcomm.qti.poweroffalarm -> vendor_qti_poweroffalarm_app (platform-signed). All variants.
PRODUCT_PACKAGES += \
    PowerOffAlarm

# Pre-grant DeskClock the (dangerous) POWER_OFF_ALARM runtime permission -- see the xml.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/PowerOffAlarm/etc/default-permissions-poweroffalarm.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/default-permissions/default-permissions-poweroffalarm.xml

ifeq ($(NX809J_MINIMAL)$(NX809J_MICROG),falsefalse)
# FullGApps: Google Clock (from gms_full.mk) has no power-off-alarm sender, so ship AOSP DeskClock
# alongside it -- it is the only alarm app that talks to PowerOffAlarm (user decision 2026-09-07).
PRODUCT_PACKAGES += \
    DeskClock
endif

# Android Auto: Google-signed AA 17.4 priv-app cluster (overrides the vendor/gms stub that
# gms_full.mk lists) + allowlist. Policy (user decision 2026-09-04):
#   FullGApps  -> ships it (the Google-tier variant; Play region-blocks the stub for some users)
#   Minimal    -> no Android Auto
#   microG     -> no Android Auto
#   PERSONAL   -> Minimal + Android Auto
NX809J_SHIP_AA := false
ifeq ($(NX809J_MINIMAL),false)
NX809J_SHIP_AA := true
endif
ifeq ($(NX809J_PERSONAL),true)
NX809J_SHIP_AA := true
endif
ifeq ($(NX809J_SHIP_AA),true)
$(call inherit-product, device/nubia/NX809J/android_auto.mk)
endif

# Circle to Search (user requirement 2026-09-03: "in full gapps and my personal build").
# Three pieces, all Google-app bound: Velvet (gms_full.mk / PERSONAL block in device.mk), the
# framework strings naming it as the ContextualSearch package (PixelConfigOverlayCommon on Full,
# overlay-personal on Personal), and the system feature android.software.contextualsearch,
# which was the missing one: found 2026-09-09 on the Personal build with everything else in
# place (Velvet 17.54 resolving LAUNCH_CONTEXTUAL_SEARCH, contextual_search_package set) but
# `pm has-feature android.software.contextualsearch` = false, so Launcher3 never armed the
# gesture. Same policy as Android Auto above: Full + Personal only.
ifeq ($(NX809J_SHIP_AA),true)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/prebuilt/etc/permissions/android.software.contextualsearch.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.software.contextualsearch.xml
endif

# Device identifiers
PRODUCT_NAME := evolution_NX809J
PRODUCT_DEVICE := NX809J
PRODUCT_BRAND := nubia
PRODUCT_MODEL := NX809J
PRODUCT_MANUFACTURER := nubia
PRODUCT_SYSTEM_NAME := NX809J

PRODUCT_GMS_CLIENTID_BASE := android-zte

# PRODUCT_BUILD_PROP_OVERRIDES removed — TARGET_DEVICE/TARGET_PRODUCT are not valid override keys

BUILD_FINGERPRINT := nubia/NX809J/NX809J:16/BP2A.250605.031.A3/V11.0.16:user/release-keys

# --- App de-bloat / de-Google (placed AFTER all inherits so filter-out sees them) ---
#  - Aperture: dropped — we ship the stock NubiaCamera (with the selfie fix)
#  - Calculator: swap Google's prebuilt for the lightweight AOSP ExactCalculator
#  - Files: drop the Google Files app (AOSP DocumentsUI stays as the file picker)
#  - Dialer: swap Google Dialer for the LineageOS Dialer (built-in auto call recording)
#  - bcr: drop Basic Call Recorder (the LineageOS Dialer records calls itself)
# (Google Contacts kept intentionally.)
# Aperture/Files/Calculator are de-bloated in BOTH variants (we ship NubiaCamera).
PRODUCT_PACKAGES := $(filter-out Aperture ApertureLensLauncher CalculatorGooglePrebuilt_85006267 FilesPrebuilt Jelly, $(PRODUCT_PACKAGES))
PRODUCT_PACKAGES += \
    ExactCalculator

ifeq ($(NX809J_MINIMAL)$(NX809J_MICROG),truefalse)
# de-Googled MINIMAL only: swap Google Dialer for the LineageOS Dialer (auto call recording).
PRODUCT_PACKAGES := $(filter-out GoogleDialer bcr, $(PRODUCT_PACKAGES))
PRODUCT_PACKAGES += \
    Dialer
endif
# FULL keeps Google Dialer (from gms_full.mk) + Google Photos — per request.

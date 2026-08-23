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
#   NX809J_MINIMAL=true  (default) → de-Googled MINIMAL: AOSP apps + Trebuchet,
#        ONLY Play Store + GMS Core (Play Integrity). WITH_GMS:=false stops
#        common_full_phone from adding a Google-app tier; gms_core.mk adds the core.
#   NX809J_MINIMAL=false           → FULL Pixel GApps (gms_full) incl. Google Dialer.
NX809J_MINIMAL ?= true

ifeq ($(NX809J_MINIMAL),true)
# de-Googled: no Google-app tier from the base
WITH_GMS := false
TARGET_USES_MINI_GAPPS := false
# Say what this build actually is. WITH_GMS=false above only suppresses the Google-app TIER;
# gms_core.mk below still adds Play Store + GMS Core + GSF, so the upstream default of "-Vanilla"
# (which means no Google at all) would be actively misleading to anyone choosing a build by name.
EVO_VERSION_SUFFIX := MinimalGApps
else
# full Pixel GApps tier
TARGET_USES_MINI_GAPPS := false
EVO_VERSION_SUFFIX := FullGApps
endif

# Inherit common LineageOS configuration (brings the AOSP app suite + Trebuchet)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

ifeq ($(NX809J_MINIMAL),true)
# Core GApps only (Play Store + GMS Core + GSF) — everything else stays AOSP.
$(call inherit-product, device/nubia/NX809J/gms_core.mk)
# Guarantee the AOSP replacements are present (idempotent if the base already adds them).
PRODUCT_PACKAGES += \
    Launcher3QuickStep \
    webview \
    Contacts \
    messaging \
    Etar \
    Jelly \
    DeskClock \
    Gallery2 \
    LatinIME
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
PRODUCT_PACKAGES := $(filter-out Aperture ApertureLensLauncher CalculatorGooglePrebuilt_85006267 FilesPrebuilt, $(PRODUCT_PACKAGES))
PRODUCT_PACKAGES += \
    ExactCalculator

ifeq ($(NX809J_MINIMAL),true)
# de-Googled MINIMAL only: swap Google Dialer for the LineageOS Dialer (auto call recording).
PRODUCT_PACKAGES := $(filter-out GoogleDialer bcr, $(PRODUCT_PACKAGES))
PRODUCT_PACKAGES += \
    Dialer
endif
# FULL keeps Google Dialer (from gms_full.mk) + Google Photos — per request.

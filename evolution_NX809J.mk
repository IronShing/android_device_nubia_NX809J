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
# We ship BOTH variants (super_minimal / super_full); flip this per build.
TARGET_USES_MINI_GAPPS := true

# Inherit common LineageOS configuration
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

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
PRODUCT_PACKAGES := $(filter-out Aperture ApertureLensLauncher CalculatorGooglePrebuilt_85006267 FilesPrebuilt GoogleDialer bcr, $(PRODUCT_PACKAGES))
PRODUCT_PACKAGES += \
    ExactCalculator \
    Dialer

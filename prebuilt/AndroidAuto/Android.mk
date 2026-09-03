# Google-signed Android Auto 17.4.663054 (174663054) as a product priv-app "cluster":
# base.apk + the three config splits Play served this device (arm64-v8a, en, xxhdpi).
# All four verified apksigner V3.1 signer sha256 1ca8dcc0... (Google, lineage from fdb00c43...).
#
# Why a cluster and not the vendor/gms stub: Play region-blocks AA for some accounts
# ("This item isn't available in your country" -- seen 2026-09-02), and Aurora is throttled
# on and off, so "update the stub yourself" is not a reliable first-run story. A system app
# directory holding base + split_*.apk is parsed as one split package
# (ApkLiteParseUtils.parseClusterPackageLite: every *.apk in the dir, filenames free, base =
# the one without a splitName). Play/Aurora still update it in place; the update keeps
# priv-app status and the allowlisted signature|privileged permissions.
#
# Why Make and not Soong: android_app_import takes exactly one apk, android_app_set wants a
# bundletool .apks with toc.pb. Make lets the splits be plain ETC prebuilts dropped into the
# same priv-app directory.
#
# Native libs are Stored (uncompressed) and extractNativeLibs=false -> loadable straight
# from the split, no lib/ extraction needed (see the preinstalled-APK JNI trap note).
# Locale: only the "en" split ships; other UI languages fall back to base strings until
# the store updates the app.
LOCAL_PATH := $(call my-dir)

AA_PRIVAPP_DIR := $(TARGET_OUT_PRODUCT)/priv-app/AndroidAutoPrebuilt

include $(CLEAR_VARS)
LOCAL_MODULE := AndroidAutoPrebuilt
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := base.apk
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_PRODUCT_MODULE := true
LOCAL_PRIVILEGED_MODULE := true
LOCAL_DEX_PREOPT := false
LOCAL_ENFORCE_USES_LIBRARIES := false
LOCAL_OVERRIDES_PACKAGES := AndroidAutoStubPrebuilt
LOCAL_REQUIRED_MODULES := \
    AndroidAutoPrebuilt_split_arm64_v8a \
    AndroidAutoPrebuilt_split_en \
    AndroidAutoPrebuilt_split_xxhdpi
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := AndroidAutoPrebuilt_split_arm64_v8a
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := split_config.arm64_v8a.apk
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_STEM := split_config.arm64_v8a.apk
LOCAL_PRODUCT_MODULE := true
LOCAL_MODULE_PATH := $(AA_PRIVAPP_DIR)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := AndroidAutoPrebuilt_split_en
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := split_config.en.apk
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_STEM := split_config.en.apk
LOCAL_PRODUCT_MODULE := true
LOCAL_MODULE_PATH := $(AA_PRIVAPP_DIR)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := AndroidAutoPrebuilt_split_xxhdpi
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := split_config.xxhdpi.apk
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_STEM := split_config.xxhdpi.apk
LOCAL_PRODUCT_MODULE := true
LOCAL_MODULE_PATH := $(AA_PRIVAPP_DIR)
include $(BUILD_PREBUILT)

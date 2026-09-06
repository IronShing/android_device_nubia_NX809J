# Fermata Auto 2.0.2 (me.aap.fermata.auto.dear.google.why) -- open-source media player whose
# "auto" flavour runs inside Android Auto (YouTube / web video on the head unit). Upstream
# release APK, developer-signed (PRESIGNED). PERSONAL builds only (evolution_NX809J.mk).
#
# Safe to preinstall: lib/arm64-v8a/*.so are Stored and extractNativeLibs=false, so the libs
# load straight from the APK (the preinstalled-APK JNI trap does not apply). Plain product app,
# not privileged. Listed by Android Auto because ComputerEngine.getInstallSourceInfo reports Play
# as the installer when gearhead asks (persist.sys.aa_fake_installsource, default on).
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := FermataAuto
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := FermataAuto.apk
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_PRODUCT_MODULE := true
LOCAL_DEX_PREOPT := false
LOCAL_ENFORCE_USES_LIBRARIES := false
include $(BUILD_PREBUILT)

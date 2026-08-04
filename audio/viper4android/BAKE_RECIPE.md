# ViPER4Android — ROM bake recipe (replaces the KSU module)

Staged in this dir: `app/ViPER4Android.apk` (the +10dB-Output-Gain rebuild), `blobs/libv4a_aidl.so`
(AIDL driver), `config/v4a_effect.xml` (the effect declaration), `sepolicy/viper4android.te`.
`Android.bp` builds the app into /product/app and the driver into /vendor/lib64/soundfx.
`viper4android.mk` adds them to PRODUCT_PACKAGES — inherit it from device.mk.

## Two things that Soong CAN'T do on a stock-vendor device (do at vendor-repack, like the chi.so bake):
1. **Merge the effect into the vendor config.** Add these two lines from `config/v4a_effect.xml` into
   `/vendor/etc/audio/sku_canoe/audio_effects_config.xml` (+ the `_stub` + `sku_alor` variants):
   - under `<libraries>`: `<library name="v4a_aidl" path="libv4a_aidl.so"/>`
   - under `<effects>`:    `<effect name="v4a_standard_aidl" library="v4a_aidl" uuid="90380da3-8536-4744-a6a3-5731970e640f" type="7261676f-6d75-7369-6364-28e2fd3ac39e"/>`
2. **Place `libv4a_aidl.so`** into the vendor image at `/vendor/lib64/soundfx/` (if not using the Soong
   cc_prebuilt above, i.e. when reusing prebuilt stock vendor). Preserve file_contexts (soundfx = same
   label as the other effect .so's).
   Repack vendor EROFS with the combined plat+vendor file_contexts (see [[vendor_repack_labeling]]).

## Global mode default (REQUIRED — else V4A silently attaches to nothing)
V4A RE per-device mode fails here: the Speaker route reports **no address**, so V4A can't attach.
**Global mode** (session-0 attach) is what makes it work. The app default is OFF. To ship it ON:
- Seed the app's DataStore preference at first boot, OR
- The pragmatic path: keep the KSU module's `service.sh` trick, OR document "enable Global mode +
  Auto-start once" in the setup. (The `persist.viper4android.*` props in the .mk are a placeholder —
  VERIFY the app actually reads them; current V4A RE stores state in its DataStore, not sysprops.)

## APK note
`app/ViPER4Android.apk` is a **debug-signed, unminified** build (~64MB). For a shipping ROM, rebuild
release+minified (needs a keystore in local.properties: KEYSTORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD)
or platform-sign, and re-drop it here. Source of the +10 edit: EffectSections.kt outputVolume
valueRange/displayRange/coerceIn 200→316 (raw316=3.162x=+10dB); build.gradle.kts signingConfig guarded.
Fork/source: github.com/likelikeslike/ViPER4Android (cloned + patched at /tmp/v4a_src this session).

## Meanwhile
The KSU module `ViPER4Android-RE-AIDL` (+ the app installed via pm) keeps V4A working on the live device
until the next full ROM build+flash. Don't remove it until the baked version is validated.

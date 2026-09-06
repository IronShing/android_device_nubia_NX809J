# ViPER4Android — ROM bake recipe (replaces the KSU module)

Staged in this dir: `app/ViPER4Android.apk` (the +10dB-Output-Gain rebuild), `blobs/libv4a_aidl.so`
(AIDL driver), `config/v4a_effect.xml` (the effect declaration), `viper4android.rc` (shm dir),
`sepolicy/viper4android.te` (reference only — NOT wired; the live rules are the vendor CIL append, below).
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
**Global mode** (session-0 attach) is what makes it work. The app default is OFF, and the app stores
the setting in its DataStore (`global_mode`), NOT in sysprops — the `persist.viper4android.*` props in
the .mk were a placeholder that upstream never read. **Every build up to 20260904 shipped V4A doing
nothing** (effect never created) because of exactly that.
Fixed 2026-09-04 by the `romDefault()` patch in `ViperRepository.kt` (part of the APK rebuild below):
`getBooleanPreference` falls back to `SystemProperties.get("persist.viper4android.global_mode" /
".autostart")` when the pref was never written. So the .mk props are now real. Verify in the app's
log (`/data/data/com.llsl.viper4android/files/Log/viper.log`): "Global effect created (aidlType=true)".

## SHM channel: sepolicy + directory (REQUIRED — else the effect runs but never gets a parameter)
The driver exchanges parameters/status with the app through 3 mmap'd files it creates under
`/data/local/tmp/v4a/` (`shell_data_file`; path hard-coded in the blob). Two things the module used to
provide and the ROM must now:
1. **hal_audio_default → shell_data_file allow rules.** hal_audio_default is a vendor domain, and on
   this stock-vendor device vendor .te never reaches the phone (`sepolicy/viper4android.te` here is
   documentation only — it is wired to nothing). The rules ship as a **vendor CIL append in
   `~/lineage-scratch/sign_a17_release.sh`** (vendor bake, marker `;; viper4android-nx809j`):
   dir `{search read open getattr write add_name remove_name}`, file `{create read write open getattr
   setattr unlink map}`. Validate any change offline with secilc against the device's own policy
   (memory `nx809j_vendor_cil_secilc_validation`) — a bad CIL is a brick here.
2. **The directory.** The driver creates the files but not the dir (`MapSingleShm: cannot open/create
   … No such file or directory`). `viper4android.rc` (product `prebuilt_etc`, in PRODUCT_PACKAGES)
   creates it at post-fs-data **from the shell domain** (`exec_background u:r:shell:s0 shell shell --
   /system/bin/sh -c "mkdir -p … && chmod 0777 …"`). A plain init `mkdir` is neverallowed (init.te:
   "Init should not be creating subdirectories in /data/local/tmp") and fails EPERM at boot; only
   shell/adbd/installd/vold may write there. Validate on a CLEAN boot (`rm -rf /data/local/tmp/v4a;
   reboot`), not on a phone where a manual test already left the dir behind.
The app side needs no policy: platform policy already gives untrusted_app r_dir on shell_data_file and
read/write/map on its files.
Symptoms when either is missing: driver log `MapSingleShm: cannot open/create`, app log `SHM file not
ready` → `Direct mmap failed, trying su fallback` → `params buf NULL` (root prompt nobody sees; audio
still plays, unprocessed). Healthy: `ls /data/local/tmp/v4a` = shm_{status,params,bulk}.bin
(audioserver:audio 0666), driver `ConfigChannel: 3 shm mapped OK`, app `Direct mmap succeeded`.

## APK note (rebuild recipe, current = 2.0.5, 2026-09-04)
`app/ViPER4Android.apk` = upstream **2.0.5** (tag `2.0.5`, github.com/likelikeslike/ViPER4Android)
+ our +10 dB Output Gain patch, release/minified (~3.8 MB), **signed with the ROM releasekey**
(`~/lineage-scratch/signkeys/keys/releasekey.{pk8,x509.pem}`, signer sha256 5d6a6448…). The earlier
2.0.2 build was signed with a throwaway "CN=NX809J V4A" keystore that lived in /tmp and is LOST — never
sign this app with anything but the releasekey again, or the next update changes signer once more
(harmless for a system app: PMS logs "System package … signature changed; retaining data").
Consequence: upstream's in-app "Check for update" (Settings menu, manual, no nag) will FIND newer
releases but installing the upstream APK over ours fails INSTALL_FAILED_UPDATE_INCOMPATIBLE — users get
V4A updates with the ROM. Driver `blobs/libv4a_aidl.so` is RE-AIDL v2.0.2, still what 2.0.5 supports.

Rebuild (host, ~12 min, needs `~/Android/sdk` with build-tools 37 + JDK 21 via sdkman):
```
cd ~/lineage-scratch && rm -rf v4a_src && git clone https://github.com/likelikeslike/ViPER4Android v4a_src
cd v4a_src && git checkout <tag>
# ROM-default patch: ViperRepository.kt getBooleanPreference -> `?: romDefault(key, default)` reading
#   persist.viper4android.global_mode / .autostart via SystemProperties reflection (see section above)
# +10 dB patch (4 spots, 200 -> 316; raw 316 = 3.162x = +10 dB):
#   app/src/main/java/com/llsl/viper4android/effect/EffectGroups.kt      outputVolume range = 1..316
#   app/src/main/java/com/llsl/viper4android/ui/screens/main/EffectSections.kt
#       valueRange = 1f..316f / displayRange = rawToDb(1)..rawToDb(316) / .coerceIn(1, 316)
#   (EffectGroups range is the preset-load clamp added in 2.0.3 -- miss it and presets snap back to +6 dB)
echo "sdk.dir=$HOME/Android/sdk" > local.properties          # no KEYSTORE_* => unsigned release
./gradlew --no-daemon assembleRelease
BT=~/Android/sdk/build-tools/37.0.0; K=~/lineage-scratch/signkeys/keys
$BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/v4a_aligned.apk
java -jar ~/android/evo12/out/host/linux-x86/framework/apksigner.jar sign --key $K/releasekey.pk8 \
  --cert $K/releasekey.x509.pem --out ../ViPER4Android-<ver>-plus10dB-release.apk /tmp/v4a_aligned.apk
```
Check `unzip -v` shows lib/arm64-v8a/*.so **Stored** + manifest extractNativeLibs=false (preinstalled-APK
JNI trap), then copy over `app/ViPER4Android.apk`.

## Status
Baked version validated on the dev phone 2026-09-04 (2.0.5, global effect + shm channel live, zero
denials). The old KSU module `ViPER4Android-RE-AIDL` is no longer needed.

# prebuilt/AndroidAuto/ — Google-signed Android Auto split cluster (not committed)

Google's proprietary release binaries; not redistributed here. `android_auto.mk` builds without
Android Auto (with a warning) when `base.apk` is absent. To include it, obtain the
`com.google.android.projection.gearhead` 17.4.663054 (versionCode 174663054) split set that Play
serves an arm64-v8a / xxhdpi / en device (e.g. from your own Play download or an APK mirror) and
place the four files here. Verify the signer is Google's (apksigner V3.1 signer sha256
`1ca8dcc0…`, lineage from `fdb00c43…`) — head units reject anything else.

| File | sha256 |
|---|---|
| `base.apk` | `a00a13aaeb15546eb31a44fcdb8188242cf387008253be89530be8adba6a6ddd` |
| `split_config.arm64_v8a.apk` | `cf7189eb9a05197e300c6c362bf14004ee3f3d25f1227991542ed83af495cc94` |
| `split_config.en.apk` | `4252f1f360be272eb6ccc3c7d2f62aea9e8390335e599f16e56e417fb8a5222f` |
| `split_config.xxhdpi.apk` | `ca1ab296460cc3d3155ba27eabd8597500c90e1082d23fa3d2c0b1a0f540f7f1` |

Signing scripts must leave these Google-signed: pass `-e split_config.arm64_v8a.apk= -e
split_config.en.apk= -e split_config.xxhdpi.apk=` to `sign_target_files_apks` (see
`sign_a17_release.sh`).

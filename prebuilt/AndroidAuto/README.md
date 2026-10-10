# prebuilt/AndroidAuto/ — Google-signed Android Auto split cluster (not committed)

Google's proprietary release binaries; not redistributed here. `android_auto.mk` builds without
Android Auto (with a warning) when `base.apk` is absent. To include it, obtain the
`com.google.android.projection.gearhead` 17.9.664004 (versionCode 179664004) split set that Play
serves an arm64-v8a / xxhdpi / en device (e.g. from your own Play download or an APK mirror) and
place the four files here. Verify the signer is Google's (apksigner V3.1 signer sha256
`1ca8dcc0…`, lineage from `fdb00c43…`) — head units reject anything else.

| File | sha256 |
|---|---|
| `base.apk` | `a17d842298912f2b7fc7265a32990475ee5e89a6949902066a2a5629d5539694` |
| `split_config.arm64_v8a.apk` | `a19c790a4970db68b885208019cf5018774eccd8198d0a02b96033c191fbee99` |
| `split_config.en.apk` | `eab1672b270c754adb25b858670d82c7cc807892db846352de08403625557d3e` |
| `split_config.xxhdpi.apk` | `b40190b52b2da324c48bfa73cee1d937b6d44db1cf6fc4af2e3040ac90d9f67b` |

Signing scripts must leave these Google-signed: pass `-e split_config.arm64_v8a.apk= -e
split_config.en.apk= -e split_config.xxhdpi.apk=` to `sign_target_files_apks` (see
`sign_a17_release.sh`).

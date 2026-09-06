# prebuilt/FermataAuto/ — Fermata Auto (not committed; *.apk is .gitignore'd)

`FermataAuto.apk` = upstream release `fermata-auto-2.0.2-arm64.apk` from
https://github.com/AndreyPavlenko/Fermata/releases/tag/2.0.2 (Apache-2.0, developer-signed).
Package `me.aap.fermata.auto.dear.google.why`, versionCode 268.
sha256 `9833785e13a8f54afecd877f06ae579d7d245bcd5d218576af98000c4089decd`

Only the PERSONAL build (`NX809J_PERSONAL=true`) preinstalls it, together with Android Auto, so
the dev's phone has YouTube on the head unit. Published variants never include it. If the file is
absent the build simply skips it (wildcard guard in evolution_NX809J.mk).

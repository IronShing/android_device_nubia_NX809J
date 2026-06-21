# NX809J (RedMagic 11 Pro) — Beta Soak Checklist

Run on a **clean build from the committed tree** + a **factory-reset device** (so it
reflects what a user flashing fresh would get — not the accumulated runtime state of
the dev unit). `[ ]` = test, `[!]` = known-not-working (document, don't flag as regression).

## 0. Build / boot integrity (gate)
- [ ] Clean `repo sync` + `apply-patches.sh` (or forks) + build with **no** manual runtime steps
- [ ] First boot to UI, no bootloop; second (warm) reboot clean
- [ ] `logcat`/`dmesg` has **no** SE/IFAA crash-loop and **no** `updatable_crashing` / RescueParty storm
- [ ] No *new* persistent AVC denials beyond the known stock-vendor cascade (permissive)

## 1. Telephony — the headline (must pass)
- [ ] SIM detected, registers on LTE
- [ ] **Outgoing call: two-way audio** (the VoLTE fix) — earpiece
- [ ] **Incoming call: two-way audio**
- [ ] Call rides **VoLTE** from a clean boot, *no* manual `ims set-ims-service` — `ImsPhoneConnection`, not GSM CSFB
- [ ] Call audio routes: earpiece / speaker / wired headset / BT headset all work + mic on each
- [ ] DTMF tones during a call
- [ ] Mobile data (4G/5G) up + throughput sane
- [ ] SMS send + receive
- [ ] MMS send + receive
- [ ] Call recording works (overlay)
- [ ] Airplane mode on/off recovers signal cleanly
- [ ] (note) VoLTE carrier config is Zain-KW (41902)-specific — other carriers may need their own overlay

## 2. Audio
- [ ] Speaker / media playback
- [ ] Wired (USB-C) headset playback + mic
- [ ] Bluetooth A2DP audio
- [ ] **LE Audio (LC3)** on dual-mode buds (the switcher fix)
- [ ] Mic in voice recorder + 3rd-party apps
- [ ] Volume rocker, ring/vibrate/silent, haptics (ZTE vibrator)

## 3. Display / input
- [ ] Auto + manual brightness
- [ ] **High refresh rate** (120/144 Hz) selectable + applied
- [ ] **AOD** works; **DT2W** wakes
- [ ] Auto-rotate, night light
- [ ] Charge-limit indicator: **defend glyph, not a charging bolt**, when capped (the fix)

## 4. Biometrics
- [ ] **Fingerprint enroll** (UDFPS) succeeds
- [ ] FP unlock (screen on)
- [ ] **FP survives a factory reset / `/data` wipe with NO manual steps** (restore-fp-cal — the new fix)
- [!] Screen-off finger-on-sensor unlock does **not** work — use DT2W-wake → finger (known)

## 5. Power / battery
- [ ] Charging works; fast charge
- [ ] **Charge limit (80%) holds** (LineageOS Charging Control)
- [ ] Battery % accurate; idle drain sane (crash-loops fixed)

## 6. Connectivity
- [ ] WiFi 2.4 / 5 / 6 GHz connect + throughput; hotspot
- [ ] Bluetooth pair/connect/reconnect
- [ ] **NFC** read + **HCE tap-to-pay** (basic NFC must still work after eSE HAL disabled)
- [ ] GPS/location lock (outdoors)
- [ ] USB: MTP file transfer, USB tethering, ADB

## 7. Camera (all paths)
- [ ] Rear photo — **every lens** (main / ultrawide / tele / macro)
- [ ] Front photo
- [ ] Video record — each resolution + stabilization; audio in video
- [ ] Flash + torch; night / portrait / slow-mo
- [ ] Camera inside a 3rd-party app (e.g. Snapchat) + QR scan

## 8. Sensors
- [ ] Rotation (accel/gyro), proximity (screen-off in call), auto-brightness (ALS), compass
- [ ] **Shoulder triggers** (SAR), **fan control**, **RGB LEDs**, **Magic slider**, micropump — the gaming hardware

## 9. System / apps
- [ ] Play Store + app install/update
- [ ] Reboot, recovery boot, fastbootd reachable (USB fix)
- [ ] Factory reset completes + boots clean

## Known limitations — put in release notes (NOT blockers)
- SELinux **permissive** (stock-vendor ride — global enforcing blocked by the vendor denial cascade)
- Screen-off UDFPS unsupported (DT2W workaround)
- Hardware **eSE / secure-element NFC disabled** (HCE/tap-to-pay still works)
- **Play Integrity / SafetyNet likely fails** (custom ROM + permissive) — banking/GPay may not work
- FP cal is **per-unit** — this is a single-unit build; a public build needs on-device self-cal
- VoLTE config is **Zain-KW specific**

## Sign-off
Beta-ready when §0, §1, §4 (enroll + survive-wipe), §5 (limit holds), §6 (NFC/HCE) pass
and the rest is either green or in the known-limitations list.

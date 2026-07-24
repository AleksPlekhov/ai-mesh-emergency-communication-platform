# ESP32-S3 BitChat Relay

A powered, transparent GATT-server relay node for the ResQMesh/BitChat BLE mesh — extends
the phone-to-phone mesh with a fixed hub that never needs a phone in the middle, and can
run Coded PHY continuously since it isn't fighting a phone's battery budget.

Firmware: [`esp32s3_bitchat_relay/esp32s3_bitchat_relay.ino`](esp32s3_bitchat_relay/esp32s3_bitchat_relay.ino)

Tested on: **ESP32-S3 N16R8 DevKitC-1** (16MB flash / 8MB PSRAM). Should work on any
ESP32-S3 board; plain ESP32 (non-S3) does **not** support Coded PHY at all and would need
mode 1M-only.

## What it does

- Advertises the same service/characteristic UUIDs as the Android app
  (`AppConstants.Mesh.Gatt`), so phones discover and connect to it exactly like they would
  another phone.
- Relays every packet it receives to every other connected phone via GATT notify,
  decrementing only the TTL byte — never touches anything else in the packet (packets are
  signed and padded).
- Never re-fragments; a phone's 517-byte MTU negotiation keeps every relay packet to one
  GATT write.
- Three selectable range/PHY modes, cycled by pressing RST, colour-coded on the onboard LED.

## Prerequisites

- **Arduino core for ESP32**, board setting: `ESP32S3 Dev Module`.
- **NimBLE-Arduino >= 2.1** (h2zero) — install via Library Manager. **Must be the 2.x
  branch**; this sketch uses 2.x-only APIs (`NimBLEExtAdvertising`, `NimBLEAttValue`,
  `NimBLEConnInfo`) that don't exist in 1.x.
- **Extended advertising must be enabled by hand** — the Arduino IDE can't pass build
  flags the way PlatformIO can, so you have to edit the library's config file directly:

  ```
  <your sketchbook>/libraries/NimBLE-Arduino/src/nimconfig.h
  ```

  Uncomment and set:
  ```cpp
  #define CONFIG_BT_NIMBLE_EXT_ADV 1
  #define CONFIG_BT_NIMBLE_MAX_EXT_ADV_INSTANCES 2   // sketch uses instances 0 and 1
  ```
  Skipping either line fails the build — the sketch has a `#error` for the first, and the
  second fails silently/asserts at runtime if left at the default of 1 (this sketch always
  uses 2: the legacy 1M floor plus the Coded-PHY set).

  **Finding your sketchbook path**: Arduino IDE → File → Preferences → "Sketchbook
  location". Don't assume it's the default `Documents/Arduino` — on this project's dev
  machine it turned out to be OneDrive-redirected
  (`OneDrive/Documentos/Arduino/libraries/...`). If you're not sure, the fastest way to
  find it for certain is to check a compiled build's dependency files under
  `%LOCALAPPDATA%/arduino/sketches/<hash>/libraries/<Lib>/*.cpp.libsdetect.d` — the first
  line lists the actual absolute path the compiler used.

  PlatformIO users can skip the manual edit and just set:
  ```ini
  build_flags = -DCONFIG_BT_NIMBLE_EXT_ADV=1 -DCONFIG_BT_NIMBLE_MAX_EXT_ADV_INSTANCES=2
  ```

## Modes

Press the RST button to cycle. State is stored in flash (NVS), so it survives both RST
and full power loss.

| Mode | LED | Behavior |
|---|---|---|
| 0 | Green | Legacy 1M advertising only (max compatibility floor) |
| 1 | Blue | 1M floor + Coded-PHY extended set, connections prefer Coded **S=2** (~500kbps, medium range) |
| 2 | Red | 1M floor + Coded-PHY extended set, connections prefer Coded **S=8** (~125kbps, max range) |

A white flash overlays whichever mode colour every time a packet is relayed.

The 1M advertisement is **never** turned off in modes 1/2 — this matches the app's own
additive Coded-PHY design (`BluetoothGattServerManager.updateMeshDensity`): BT4-only
phones must always be able to find the node. Coded PHY is a supplementary set layered on
top, not a replacement.

Why RST-cycling uses NVS flash instead of RTC memory: on this board, the RST button reset
is hardware-classified as `POWERON` all the way down at the ROM bootloader
(`rst:0x1 (POWERON)`), not a distinct external-reset cause — and ESP-IDF only guarantees
RTC memory retention across deep-sleep wake, not an EN-pin reset or power-on. An earlier
version of this firmware tried `RTC_NOINIT_ATTR` + reset-reason branching and the mode
never actually advanced, because the "cold boot" branch fired on every single reset. NVS
(flash) survives both RST and real power loss, so it's the only persistence that actually
works here.

## Serial log format

```
Reset reason: POWERON (1)
ResQMesh relay starting — mode 2 (1M + Coded, S=8 pref)
Press RST to cycle mode: GREEN 1M / BLUE Coded S=2 / RED Coded S=8
Advertising started.
[14:32:07] Connected: 5c:54:54:62:f5:1c (total 1)
[14:32:07] PHY pref (coded S=8): rc=0
[14:32:10] RSSI  5c:54:54:62:f5:1c: -58 dBm  ~8.2m  (mode 2)
[14:35:41] Disconnected: 5c:54:54:62:f5:1c (reason 531)
[14:32:37] Stats: relayed=11 dropped=17 conns=1 heap=238560
```

- `[HH:MM:SS]` prefix picks the best available time source automatically, in order:
  1. **Phone-derived** — every packet already carries the sender's epoch-millisecond
     timestamp in its header (`BinaryProtocol.kt`'s `timestamp` field); the relay was
     already parsing that field for dedup, so it's harvested for free from ordinary
     traffic the moment any phone connects and sends anything. No WiFi, no app changes.
  2. **NTP** — if you set `WIFI_SSID`/`WIFI_PASSWORD` near the top of the sketch, it
     connects once at boot, syncs time, then **fully powers WiFi off** before BLE starts
     (the S3 shares one 2.4GHz radio between WiFi and BLE — leaving WiFi on would degrade
     the exact range you're trying to measure). Useful for a solo bench test before any
     phone has connected.
  3. **Elapsed-seconds** (`+123.4s`) — fallback when neither of the above is available;
     needs nothing at all, which matches real field deployment with no infrastructure.
  - Set `GMT_OFFSET_SEC` to your local UTC offset (seconds) if you want the phone-derived
    or NTP time to read as local time instead of UTC.
- `PHY pref ... rc=0` — `rc` is the raw NimBLE return code for the PHY-preference request;
  0 means the S3 controller accepted it. A nonzero value means the mode's coded PHY
  request was rejected and the connection is likely still running 1M.
- `Stats:` line prints every 30s: cumulative relayed/dropped packet counts, current
  connection count, and free heap. Growing `dropped` alongside `relayed` is expected under
  normal mesh flooding (multiple phones re-relay the same packet by different paths; the
  dedup cache is supposed to catch and drop the repeats) — only worry if `relayed` stalls
  or heap trends downward over time.

## Range / RSSI logging

Every 3 seconds while any phone is connected, `logRssi()` prints RSSI (via
`ble_gap_conn_rssi`) and a rough distance estimate for each connected peer.

The distance estimate uses the standard log-distance path-loss model:
```
distance = 10 ^ ((RSSI_REF_AT_1M[mode] − rssi) / (10 × PATH_LOSS_EXPONENT[mode]))
```
Both constants are **per-mode arrays** (indexed 0/1/2 to match the PHY mode) near the top
of the sketch, because the phone's controller may trim TX power on Coded PHY and this
radio's own RSSI reporting can carry a small per-PHY gain offset — cheaper to calibrate
each mode separately than assume they share a curve.

**To calibrate**: for each mode, stand exactly 1m from the ESP in open air (no walls
between), read the RSSI it logs, and set that mode's `RSSI_REF_AT_1M` entry to the observed
value. Set `PATH_LOSS_EXPONENT` per mode based on your test environment: ~2.0 free
space/line-of-sight, ~2.5–3.0 open indoor space, ~3.5–4.5 through walls/obstructions.

**Accuracy caveat — read before trusting the numbers**: RSSI fades ±4–6dB from multipath
and body/hand blocking alone at a *fixed* distance, which is roughly a ±30–50% swing in
the estimated meters. Treat this as a near/medium/far indicator, not a tape measure. Some
phones also implement BLE 5.2 LE Power Control, which actively holds RSSI near a target
instead of letting it fade with distance — if so, these numbers will barely move
regardless of mode or actual distance. There is no way to get genuinely precise
(sub-meter) relative positioning out of this hardware: BLE Direction Finding (AoA/AoD) and
Bluetooth Channel Sounding both need radio hardware this chip's BLE controller doesn't
implement, and UWB is a completely separate radio you'd have to add via an external module.

Typical Coded PHY S=8 sensitivity floor on BLE5 radios is around −103 to −105 dBm (vs
roughly −95 to −97 dBm for 1M) — a reading in that neighbourhood means you're near the
practical maximum range for that mode, not a bug.

## Known limitations

- **Peripheral-only** — the relay never scans or connects out, so two of these boards
  placed near each other will *not* automatically bridge each other. Extending range
  ESP-to-ESP would need one side to add a GATT-client role (scan for the service UUID,
  connect like a phone would, subscribe to notify) — not yet implemented.
- **No multi-anchor positioning** — RSSI-based ranging here is single-link only. A
  building-scale deployment with 3+ of these at known fixed positions could in principle
  trilaterate an approximate phone position from simultaneous RSSI readings, but that
  solver doesn't exist yet.
- LED pin is `GPIO48` on most S3-DevKitC-1 boards, but `GPIO38` on the v1.1 board revision
  — change `LED_PIN` in the sketch if the LED stays dark.

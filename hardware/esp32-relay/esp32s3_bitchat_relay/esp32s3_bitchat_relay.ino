/*
 * ResQMesh BLE relay node — ESP32-S3 (tested target: S3 N16R8 DevKitC-1)
 *
 * A transparent GATT-server relay hub for the ResQMesh/BitChat Android mesh:
 * phones discover the service UUID, connect, subscribe to notifications, and
 * write packets; every packet written by one phone is re-notified to all other
 * subscribed phones with TTL decremented. The relay never modifies any other
 * byte (packets are signed + padded), never re-fragments, and never splits a
 * packet across writes (the app parses one packet per GATT write).
 *
 * ── Modes (cycled by pressing the RST button, persisted in NVS flash) ───────
 *   0  GREEN  — legacy 1M advertising only (max compatibility floor)
 *   1  BLUE   — 1M floor + Coded-PHY extended advertising set,
 *               connections prefer Coded S=2 (~500 kbps, medium range)
 *   2  RED    — 1M floor + Coded-PHY extended advertising set,
 *               connections prefer Coded S=8 (~125 kbps, max range)
 *   White flash = packet relayed. First boot after flashing starts in mode 0;
 *   every subsequent boot (RST button OR power cycle) advances to the next
 *   mode, wrapping back to 0 after mode 2.
 *
 *   Why NVS and not RTC memory: on this board (and apparently several
 *   ESP32-S3 DevKitC boards) the RST button reset is hardware-classified as
 *   POWERON all the way down at the ROM bootloader ("rst:0x1 (POWERON)"),
 *   not as a distinct EN/external-reset cause — and RTC memory retention is
 *   only guaranteed by ESP-IDF across deep-sleep wake, not across an EN-pin
 *   reset or power-on. So this can't reliably tell "RST press" apart from
 *   "power cycle" via reset reason, and RTC_NOINIT_ATTR isn't guaranteed to
 *   survive either one on this hardware. NVS (flash) always survives both.
 *
 *   Note: the S=2/S=8 choice is a *connection* PHY preference (LE Set PHY).
 *   The coding scheme of the advertising itself is chosen by the controller
 *   (typically S=8) — the host API on the S3 has no knob for it.
 *
 * ── Build requirements ──────────────────────────────────────────────────────
 *   - Arduino core for ESP32 (3.x), board: "ESP32S3 Dev Module"
 *   - NimBLE-Arduino >= 2.1 (h2zero)
 *   - Extended advertising MUST be enabled in NimBLE:
 *       Arduino IDE : uncomment  #define CONFIG_BT_NIMBLE_EXT_ADV 1
 *                     in  <libraries>/NimBLE-Arduino/src/nimconfig.h
 *       PlatformIO  : build_flags = -DCONFIG_BT_NIMBLE_EXT_ADV=1
 *
 * ── LED ─────────────────────────────────────────────────────────────────────
 *   WS2812 on GPIO48 on most S3-DevKitC-1 N16R8 boards (GPIO38 on the v1.1
 *   board revision — change LED_PIN below if your LED stays dark).
 */

#include <NimBLEDevice.h>
#include "host/ble_gap.h"   // ble_gap_set_prefered_le_phy + PHY masks/options
#include <esp_system.h>
#include <Preferences.h>
#include <WiFi.h>
#include <time.h>
#include <algorithm>
#include <cmath>
#include <deque>
#include <set>
#include <string>
#include <vector>

#ifndef CONFIG_BT_NIMBLE_EXT_ADV
#error "Enable extended advertising: define CONFIG_BT_NIMBLE_EXT_ADV=1 (see header comment)"
#endif

// ── App protocol constants (must match AppConstants.Mesh.Gatt / BinaryProtocol.kt)
static const char* SERVICE_UUID        = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C";
static const char* CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D";

// v1 header: ver(1) type(1) ttl(1) ts(8) flags(1) len(2)  → senderID at 14
// v2 header: ver(1) type(1) ttl(1) ts(8) flags(1) len(4)  → senderID at 16
#define TTL_INDEX        2
#define SENDER_OFF_V1    14
#define SENDER_OFF_V2    16
#define SENDER_ID_SIZE   8
#define MAX_PACKET_SIZE  600   // app negotiates MTU 517; anything bigger is app-level FRAGMENTed
#define DEDUP_CAPACITY   512
#define RELAY_QUEUE_LEN  24

// ── Modes / LED ─────────────────────────────────────────────────────────────
#define MODE_1M_ONLY     0
#define MODE_CODED_S2    1
#define MODE_CODED_S8    2
#define MODE_COUNT       3

#ifdef RGB_BUILTIN
#define LED_PIN RGB_BUILTIN
#else
#define LED_PIN 48            // set to 38 on DevKitC-1 v1.1
#endif

#define ADV_INSTANCE_LEGACY 0
#define ADV_INSTANCE_CODED  1

// Mode persistence across RST presses AND power cycles — see header comment
// for why this is NVS-backed rather than RTC memory.
#define NVS_NAMESPACE "resqrelay"
#define NVS_KEY_MODE  "mode"

static uint8_t  mode = MODE_1M_ONLY;
static uint32_t ledFlashUntil = 0;

// ── RSSI → distance estimate (log-distance path-loss model) ─────────────────
// distance = 10 ^ ((RSSI_REF_AT_1M[mode] - rssi) / (10 * PATH_LOSS_EXPONENT[mode]))
//
// One entry per mode (0=1M, 1=Coded S=2, 2=Coded S=8) because the phone's
// controller may trim TX power on Coded PHY, and this radio's RSSI reporting
// can carry a small per-PHY gain offset — cheaper to just calibrate each mode
// separately than assume they share a curve.
//
// CALIBRATE EACH ROW before trusting the numbers: in that mode, stand exactly
// 1m from the ESP in open air (no walls between), read the RSSI it logs, and
// set that mode's RSSI_REF_AT_1M entry to the observed value. The defaults
// below are only generic placeholders for a phone at ~0dBm TX and this ESP's
// +9dBm RX chain.
//
// PATH_LOSS_EXPONENT models the environment: ~2.0 free space/line-of-sight,
// ~2.5-3.0 open indoor space, ~3.5-4.5 through walls/obstructions. Pick
// whichever matches where you're actually testing; it does not auto-adapt.
//
// Accuracy caveat: RSSI fades ±4-6dB from multipath and body/hand blocking
// alone at a FIXED distance, which is roughly a ±30-50% swing in the
// estimated meters. Treat this as "near / medium / far", not a tape measure.
// Also worth checking: some phones implement BLE 5.2 LE Power Control, which
// actively holds RSSI near a target instead of letting it fade with distance
// — if so, these numbers will barely move regardless of mode or distance.
static const float RSSI_REF_AT_1M[MODE_COUNT]     = { -46.0f, -46.0f, -46.0f };
static const float PATH_LOSS_EXPONENT[MODE_COUNT] = {   3.0f,   3.0f,   3.0f };

static float rssiToDistanceMeters(int rssi) {
  return powf(10.0f, (RSSI_REF_AT_1M[mode] - (float)rssi) / (10.0f * PATH_LOSS_EXPONENT[mode]));
}

static NimBLEServer*         pServer = nullptr;
static NimBLECharacteristic* pChar   = nullptr;

struct RelayItem { uint8_t* buf; uint16_t len; };
static QueueHandle_t relayQueue = nullptr;

// FIFO-evicting dedup: key = senderID(8) + timestamp bytes(8) + type(1)
static std::set<std::string>   seenSet;
static std::deque<std::string> seenFifo;

static uint32_t relayedCount = 0, droppedCount = 0;

// Connected peers, tracked for periodic RSSI logging during range testing.
struct PeerConn { uint16_t handle; std::string addr; };
static std::vector<PeerConn> peers;

// ── Wall-clock time (optional) ───────────────────────────────────────────────
// Fill in WiFi credentials to sync real time via NTP once at boot, so log
// timestamps read as HH:MM:SS matching your phone's clock instead of seconds
// since boot. WiFi is connected ONLY long enough to sync, then fully powered
// off before BLE starts: the S3 shares one 2.4GHz radio between WiFi and
// BLE, and leaving WiFi on would degrade the exact BLE range you're testing.
//
// Leave WIFI_SSID empty ("") to skip this — falls back to elapsed-seconds
// timestamps, no network required at all (matches the app's no-infra ethos
// for actual field use; this is really only for at-home calibration walks).
#define WIFI_SSID            ""   // <-- fill in for HH:MM:SS timestamps
#define WIFI_PASSWORD         ""
#define NTP_SERVER            "pool.ntp.org"
#define GMT_OFFSET_SEC        0   // <-- your local UTC offset in seconds (e.g. -3*3600)
#define DAYLIGHT_OFFSET_SEC   0

static bool timeIsSynced = false;

static void syncTimeThenDisableWifi() {
  if (strlen(WIFI_SSID) == 0) {
    Serial.println("No WIFI_SSID set - using elapsed-seconds timestamps.");
    return;
  }
  Serial.printf("Connecting to WiFi '%s' for one-time NTP sync...\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 10000) {
    delay(200);
  }

  if (WiFi.status() == WL_CONNECTED) {
    configTime(GMT_OFFSET_SEC, DAYLIGHT_OFFSET_SEC, NTP_SERVER);
    struct tm timeinfo;
    if (getLocalTime(&timeinfo, 8000)) {
      timeIsSynced = true;
      Serial.println("Time synced via NTP.");
    } else {
      Serial.println("NTP sync timed out - using elapsed-seconds timestamps.");
    }
  } else {
    Serial.println("WiFi connect failed - using elapsed-seconds timestamps.");
  }

  // Fully release the radio so it never shares airtime with BLE afterward.
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
}

// ── Phone-derived wall clock (preferred — needs no WiFi/infra at all) ───────
// Every packet already carries the sender's epoch-millisecond timestamp in
// its header (BinaryProtocol.kt's `timestamp` field, bytes 3-10) — harvested
// for free from ordinary relay traffic in preprocessPacket(), no new
// characteristic or app change required. Updated on every well-formed packet
// (regardless of dup/TTL outcome), so it also self-corrects for millis()
// drift over a long test and adapts if a different phone connects later.
static bool    phoneTimeSynced   = false;
static int64_t wallClockOffsetMs = 0;   // epochMs - millis() at last sync

static void harvestPhoneTime(const uint8_t* d) {
  uint64_t epochMs = 0;
  for (int i = 0; i < 8; i++) epochMs = (epochMs << 8) | d[3 + i];
  // Sanity window: roughly year-2023 to year-2100 in epoch ms, so a
  // malformed/adversarial packet can't wedge the clock to nonsense.
  if (epochMs < 1700000000000ULL || epochMs > 4102444800000ULL) return;
  wallClockOffsetMs = (int64_t)epochMs - (int64_t)millis();
  phoneTimeSynced   = true;
}

// Fills buf with "HH:MM:SS" wall-clock time — from a connected phone's own
// packets if we've seen one, else from NTP if that synced at boot, else
// "+123.4s" elapsed since boot. Either way, every log line below is
// time-correlatable against a walked-distance log kept by hand or on a phone.
static void timestampStr(char* buf, size_t bufSize) {
  if (phoneTimeSynced) {
    int64_t nowMs = (int64_t)millis() + wallClockOffsetMs + (int64_t)GMT_OFFSET_SEC * 1000;
    time_t nowSec = (time_t)(nowMs / 1000);
    struct tm timeinfo;
    gmtime_r(&nowSec, &timeinfo);   // GMT_OFFSET_SEC already applied above
    strftime(buf, bufSize, "%H:%M:%S", &timeinfo);
    return;
  }
  if (timeIsSynced) {
    struct tm timeinfo;
    if (getLocalTime(&timeinfo, 0)) {
      strftime(buf, bufSize, "%H:%M:%S", &timeinfo);
      return;
    }
  }
  snprintf(buf, bufSize, "+%.1fs", millis() / 1000.0f);
}

// ────────────────────────────────────────────────────────────────────────────
static void ledShow(uint8_t r, uint8_t g, uint8_t b) { neopixelWrite(LED_PIN, r, g, b); }

static void ledModeColor() {
  switch (mode) {
    case MODE_1M_ONLY:  ledShow(0, 25, 0);  break;  // green
    case MODE_CODED_S2: ledShow(0, 0, 30);  break;  // blue
    case MODE_CODED_S8: ledShow(30, 0, 0);  break;  // red
  }
}

static const char* resetReasonName(esp_reset_reason_t rr) {
  switch (rr) {
    case ESP_RST_POWERON:   return "POWERON";
    case ESP_RST_EXT:       return "EXT";
    case ESP_RST_SW:        return "SW";
    case ESP_RST_PANIC:     return "PANIC";
    case ESP_RST_INT_WDT:   return "INT_WDT";
    case ESP_RST_TASK_WDT:  return "TASK_WDT";
    case ESP_RST_WDT:       return "WDT";
    case ESP_RST_DEEPSLEEP: return "DEEPSLEEP";
    case ESP_RST_BROWNOUT:  return "BROWNOUT";
    case ESP_RST_SDIO:      return "SDIO";
    default:                return "UNKNOWN";
  }
}

// Prints current RSSI for every connected peer — for range-testing, correlate
// this against the mode (green/blue/red) and your own paced-out distance.
static void logRssi() {
  char ts[16];
  timestampStr(ts, sizeof(ts));
  for (auto& p : peers) {
    int8_t rssi = 0;
    int rc = ble_gap_conn_rssi(p.handle, &rssi);
    if (rc == 0) {
      float dist = rssiToDistanceMeters(rssi);
      Serial.printf("[%s] RSSI  %s: %d dBm  ~%.1fm  (mode %u)\n",
                    ts, p.addr.c_str(), (int)rssi, dist, mode);
    } else {
      Serial.printf("[%s] RSSI  %s: unavailable (rc=%d)\n", ts, p.addr.c_str(), rc);
    }
  }
}

// Advances the mode on every boot and persists it to flash (NVS), so it
// survives RST-button presses and power cycles alike. Logs the hardware
// reset reason purely for diagnostics — it is not used for any decision,
// since this board reports the RST button as indistinguishable from a
// genuine power-on reset (see header comment).
static void pickModeFromNvs() {
  Serial.printf("Reset reason: %s (%d)\n",
                resetReasonName(esp_reset_reason()), (int)esp_reset_reason());

  Preferences prefs;
  prefs.begin(NVS_NAMESPACE, false);
  uint8_t stored = prefs.getUChar(NVS_KEY_MODE, 0xFF);
  mode = (stored > MODE_COUNT) ? MODE_1M_ONLY               // first boot ever
                                : (uint8_t)((stored + 1) % MODE_COUNT);
  prefs.putUChar(NVS_KEY_MODE, mode);
  prefs.end();
}

// Validate a packet in place, dedup it, and decrement its TTL.
// Returns false when the packet must be dropped (malformed / dup / dead).
static bool preprocessPacket(uint8_t* d, size_t n) {
  if (n < SENDER_OFF_V1 + SENDER_ID_SIZE || n > MAX_PACKET_SIZE) return false;
  uint8_t version = d[0];
  size_t senderOff;
  if      (version == 1) senderOff = SENDER_OFF_V1;
  else if (version == 2) senderOff = SENDER_OFF_V2;
  else return false;
  if (n < senderOff + SENDER_ID_SIZE) return false;

  harvestPhoneTime(d);   // opportunistic, regardless of dup/TTL outcome below

  if (d[TTL_INDEX] == 0) return false;   // dead on arrival

  std::string key;
  key.reserve(17);
  key.append((const char*)d + senderOff, SENDER_ID_SIZE); // senderID
  key.append((const char*)d + 3, 8);                      // timestamp
  key.push_back((char)d[1]);                              // type
  if (seenSet.count(key)) return false;
  seenSet.insert(key);
  seenFifo.push_back(key);
  if (seenFifo.size() > DEDUP_CAPACITY) {
    seenSet.erase(seenFifo.front());
    seenFifo.pop_front();
  }

  d[TTL_INDEX]--;  // the only byte a relay may touch (signature covers the rest)
  return true;
}

// ── Advertising ─────────────────────────────────────────────────────────────
static void startAdvertising() {
  NimBLEExtAdvertising* adv = NimBLEDevice::getAdvertising();

  // Instance 0 — legacy 1M floor, always on (matches the app's additive design:
  // BT4-era phones must always be able to discover the node).
  NimBLEExtAdvertisement legacyAdv;
  legacyAdv.setLegacyAdvertising(true);
  legacyAdv.setConnectable(true);
  legacyAdv.setScannable(true);          // legacy connectable must be scannable
  legacyAdv.setCompleteServices(NimBLEUUID(SERVICE_UUID));

  // Scan response carries an 8-byte node ID as service data, mirroring the
  // app's advertisement (the client reads it to identify/dedupe peers).
  uint64_t mac = ESP.getEfuseMac();
  std::string nodeId((const char*)&mac, 8);
  NimBLEExtAdvertisement scanRsp;
  scanRsp.setServiceData(NimBLEUUID(SERVICE_UUID), nodeId);

  adv->setInstanceData(ADV_INSTANCE_LEGACY, legacyAdv);
  adv->setScanResponseData(ADV_INSTANCE_LEGACY, scanRsp);
  adv->start(ADV_INSTANCE_LEGACY);

  // Instance 1 — supplementary Coded-PHY extended set (modes 1 & 2).
  // A powered relay can leave this on continuously; the 3s/42s duty cycle in
  // the app exists only to protect phone batteries.
  if (mode != MODE_1M_ONLY) {
    NimBLEExtAdvertisement codedAdv(BLE_HCI_LE_PHY_CODED, BLE_HCI_LE_PHY_CODED);
    codedAdv.setConnectable(true);
    codedAdv.setScannable(false);        // extended connectable can't be scannable
    codedAdv.setCompleteServices(NimBLEUUID(SERVICE_UUID));
    codedAdv.setServiceData(NimBLEUUID(SERVICE_UUID), nodeId);
    adv->setInstanceData(ADV_INSTANCE_CODED, codedAdv);
    adv->start(ADV_INSTANCE_CODED);
  }
}

// ── GATT callbacks ──────────────────────────────────────────────────────────
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    char ts[16]; timestampStr(ts, sizeof(ts));
    Serial.printf("[%s] Connected: %s (total %d)\n", ts,
                  connInfo.getAddress().toString().c_str(), server->getConnectedCount());
    peers.push_back({connInfo.getConnHandle(), connInfo.getAddress().toString()});

    if (mode != MODE_1M_ONLY) {
      uint16_t opts = (mode == MODE_CODED_S8) ? BLE_GAP_LE_PHY_CODED_S8
                                              : BLE_GAP_LE_PHY_CODED_S2;
      uint8_t phys = BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_CODED_MASK;
      int rc = ble_gap_set_prefered_le_phy(connInfo.getConnHandle(), phys, phys, opts);
      Serial.printf("[%s] PHY pref (coded %s): rc=%d\n", ts,
                    mode == MODE_CODED_S8 ? "S=8" : "S=2", rc);
    }
    // A connectable advertising instance stops once a connection is made —
    // restart so more phones can join the hub.
    startAdvertising();
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& connInfo, int reason) override {
    char ts[16]; timestampStr(ts, sizeof(ts));
    Serial.printf("[%s] Disconnected: %s (reason %d)\n", ts,
                  connInfo.getAddress().toString().c_str(), reason);
    uint16_t handle = connInfo.getConnHandle();
    peers.erase(std::remove_if(peers.begin(), peers.end(),
                                [handle](const PeerConn& p) { return p.handle == handle; }),
                peers.end());
    startAdvertising();
  }
};

class RelayCharCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue v = c->getValue();
    if (v.size() == 0) return;

    uint8_t* copy = (uint8_t*)malloc(v.size());
    if (!copy) return;
    memcpy(copy, v.data(), v.size());

    if (!preprocessPacket(copy, v.size())) { free(copy); droppedCount++; return; }

    RelayItem item = { copy, (uint16_t)v.size() };
    if (xQueueSend(relayQueue, &item, 0) != pdTRUE) { free(copy); droppedCount++; }
  }
};

// ────────────────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  syncTimeThenDisableWifi();  // WiFi is fully off again by the time this returns
  pickModeFromNvs();
  ledModeColor();
  Serial.printf("\nResQMesh relay starting — mode %u (%s)\n", mode,
                mode == MODE_1M_ONLY ? "1M only" :
                mode == MODE_CODED_S2 ? "1M + Coded, S=2 pref" : "1M + Coded, S=8 pref");
  Serial.println("Press RST to cycle mode: GREEN 1M / BLUE Coded S=2 / RED Coded S=8");

  relayQueue = xQueueCreate(RELAY_QUEUE_LEN, sizeof(RelayItem));

  NimBLEDevice::init("RQM-RELAY");
  NimBLEDevice::setMTU(517);      // match the app's requestMtu(517)
  NimBLEDevice::setPower(9);      // dBm; S3 controller accepts higher if your region allows

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());
  // Keep advertising while connected is handled by restarting in the callbacks.

  NimBLEService* svc = pServer->createService(SERVICE_UUID);
  pChar = svc->createCharacteristic(
      CHARACTERISTIC_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE |
      NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::NOTIFY);
  // NimBLE creates the 0x2902 CCCD automatically for NOTIFY characteristics —
  // the app only treats a connection as "up" after subscribing to it.
  pChar->setCallbacks(new RelayCharCallbacks());
  svc->start();

  startAdvertising();
  Serial.println("Advertising started.");
}

void loop() {
  RelayItem item;
  // Fan-out happens here, never in the BLE host task.
  while (xQueueReceive(relayQueue, &item, pdMS_TO_TICKS(20)) == pdTRUE) {
    // Notify every subscribed phone. This includes the writer, which is safe:
    // the app drops its own senderID and dedups already-seen packets — same
    // semantics as mesh flooding between phones.
    pChar->setValue(item.buf, item.len);
    pChar->notify();
    free(item.buf);
    relayedCount++;
    ledShow(25, 25, 25);
    ledFlashUntil = millis() + 60;
  }

  if (ledFlashUntil && millis() > ledFlashUntil) {
    ledFlashUntil = 0;
    ledModeColor();
  }

  static uint32_t lastRssi = 0;
  if (!peers.empty() && millis() - lastRssi > 3000) {
    lastRssi = millis();
    logRssi();
  }

  static uint32_t lastStats = 0;
  if (millis() - lastStats > 30000) {
    lastStats = millis();
    char ts[16]; timestampStr(ts, sizeof(ts));
    Serial.printf("[%s] Stats: relayed=%lu dropped=%lu conns=%d heap=%lu\n",
                  ts, (unsigned long)relayedCount, (unsigned long)droppedCount,
                  pServer->getConnectedCount(), (unsigned long)ESP.getFreeHeap());
  }
}

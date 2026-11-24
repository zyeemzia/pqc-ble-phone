// ML-KEM-768 key establishment over Bluetooth Low Energy: ESP32 peripheral.
//
// The ESP32 is the IoT device (GATT server). An Android phone is the central
// (GATT client). Per handshake the ESP32 generates a fresh ML-KEM-768 key pair,
// streams the 1184 B public key to the phone as notifications, receives the
// 1088 B ciphertext the phone produced with ML-KEM encapsulation, decapsulates
// it, and reports its timings plus a short tag of the shared secret. The phone
// computes the same tag from its own shared secret, so every logged handshake is
// checked end to end (a mismatch is a failed handshake, never a data point).
//
// The ATT attribute value limit is 512 B, so neither key nor ciphertext fits in
// one characteristic value. Both are therefore sent as a stream of ATT PDUs of
// at most (MTU - 3) bytes, in order, over the single link. This fragmentation is
// exactly the cost the study measures.
//
// GATT service 7a1e0001-...:
//   CTRL  7a1e0002  Write        'K'            start a handshake
//                                'F' <mhz u16>  set CPU clock (80/160/240) between handshakes
//   PK    7a1e0003  Notify       public key, in chunks of <= MTU-3 bytes
//   CT    7a1e0004  Write / WriteNR  ciphertext, in chunks, in order
//   RES   7a1e0005  Read/Notify  20 B result, little-endian:
//         [0]     status  'O' ok, 'E' error
//         [1..4]  keygen_us        (ESP32 compute)
//         [5..8]  pk_send_us       (time to hand all PK chunks to the BLE stack)
//         [9..12] ct_rx_us         (first CT chunk arrives -> last CT chunk arrives)
//         [13..16] decaps_us       (ESP32 compute)
//         [17..18] cpu_mhz
//         [19]    first byte of SHA3-256(shared secret) (phone checks it)
//   Serial (115200): one CSV line per handshake, prefixed "BLE,", see below.
//
// Timings use esp_timer (1 us, independent of the CPU clock). The phone
// measures the end-to-end latency that the user actually experiences.
#include <Arduino.h>
#include <BLE.h>
#include "esp_timer.h"
extern "C" {
#include "api.h"
#include "fips202.h"
}

// CLEAN ML-KEM-768 overflows the default 8 kB loop-task stack.
SET_LOOP_TASK_STACK_SIZE(48 * 1024);

#define PK_BYTES PQCLEAN_MLKEM768_CLEAN_CRYPTO_PUBLICKEYBYTES
#define SK_BYTES PQCLEAN_MLKEM768_CLEAN_CRYPTO_SECRETKEYBYTES
#define CT_BYTES PQCLEAN_MLKEM768_CLEAN_CRYPTO_CIPHERTEXTBYTES
#define SS_BYTES PQCLEAN_MLKEM768_CLEAN_CRYPTO_BYTES
#define RES_BYTES 20
// ATT caps an attribute value at 512 B (Core Spec Vol 3 Part F 3.2.9). With
// MTU 517 the PDU payload could be 514 B, but Android drops notifications longer
// than 512 B (observed), so chunks are min(MTU-3, 512).
#define PK_CHUNK_CAP 512

static const BLEUUID SVC_UUID("7a1e0001-5c2b-4c7e-9f3a-1d2e3f405a01");
static const BLEUUID CTRL_UUID("7a1e0002-5c2b-4c7e-9f3a-1d2e3f405a01");
static const BLEUUID PK_UUID("7a1e0003-5c2b-4c7e-9f3a-1d2e3f405a01");
static const BLEUUID CT_UUID("7a1e0004-5c2b-4c7e-9f3a-1d2e3f405a01");
static const BLEUUID RES_UUID("7a1e0005-5c2b-4c7e-9f3a-1d2e3f405a01");

static BLECharacteristic ctrlChr, pkChr, ctChr, resChr;

static uint8_t pk[PK_BYTES], sk[SK_BYTES], ct[CT_BYTES], ss[SS_BYTES];

// Shared between BLE callbacks (BLE task) and loop().
static volatile bool connected = false;
static volatile uint16_t connHandle = 0;
static volatile uint16_t mtu = 23;
static volatile uint16_t intervalUnits = 0;   // x 1.25 ms
static volatile bool startReq = false;
static volatile uint16_t freqReq = 0;
static volatile size_t ctLen = 0;
static volatile bool ctOverflow = false;
static volatile int64_t ctFirstUs = 0, ctLastUs = 0;
static volatile bool expectingCt = false;
static uint32_t handshakes = 0;

static void putU32(uint8_t *p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (v >> (8 * i)) & 0xff; }

// Notify one chunk to the connected phone, retrying while the stack's queue is
// full. Returns false if the link is gone.
// Notify one chunk and wait until the stack reports it sent (onStatus
// SUCCESS_NOTIFY) before returning. Without this wait, back-to-back notify()
// calls let the stack drop all but the last chunk (observed: the phone received
// only the final 156 B of the 1184 B key). Returns false if the link is gone or
// the stack reports an error.
static volatile int notifyState = 0;   // 0 pending, 1 sent, -1 error
static volatile uint32_t notifyCode = 0;

static bool notifyChunk(BLECharacteristic &chr, const uint8_t *d, size_t n) {
  BTStatus st = BTStatus::Fail;
  for (int tries = 0; tries < 2000; tries++) {
    if (!connected) { Serial.println("# notify: link down"); return false; }
    notifyState = 0;
    st = chr.notify(connHandle, d, n);
    if (st) break;
    delay(1);
  }
  if (!st) { Serial.printf("# notify: not accepted, len=%u status=%d\n", (unsigned)n, (int)st); return false; }
  uint32_t w0 = millis();
  while (notifyState == 0 && connected && millis() - w0 < 3000) delay(0);
  if (notifyState != 1) {
    Serial.printf("# notify: no send confirmation, len=%u state=%d code=%u\n", (unsigned)n, (int)notifyState, (unsigned)notifyCode);
    return false;
  }
  return true;
}

static void sendResult(char status, uint32_t kg, uint32_t pkSend, uint32_t ctRx, uint32_t dec, uint8_t tag) {
  uint8_t r[RES_BYTES];
  r[0] = (uint8_t)status;
  putU32(r + 1, kg);
  putU32(r + 5, pkSend);
  putU32(r + 9, ctRx);
  putU32(r + 13, dec);
  uint16_t mhz = getCpuFrequencyMhz();
  r[17] = mhz & 0xff;
  r[18] = mhz >> 8;
  r[19] = tag;
  resChr.setValue(r, sizeof r);
  notifyChunk(resChr, r, sizeof r);
}

static void runHandshake() {
  // 1. fresh key pair
  int64_t t = esp_timer_get_time();
  PQCLEAN_MLKEM768_CLEAN_crypto_kem_keypair(pk, sk);
  uint32_t keygen_us = (uint32_t)(esp_timer_get_time() - t);

  // 2. stream the public key
  ctLen = 0; ctOverflow = false; ctFirstUs = 0; ctLastUs = 0;
  expectingCt = true;
  size_t chunk = (mtu > 3) ? (size_t)(mtu - 3) : 20;
  if (chunk > PK_CHUNK_CAP) chunk = PK_CHUNK_CAP;
  t = esp_timer_get_time();
  for (size_t off = 0; off < PK_BYTES; off += chunk) {
    size_t n = min(chunk, (size_t)(PK_BYTES - off));
    if (!notifyChunk(pkChr, pk + off, n)) { expectingCt = false; Serial.printf("# pk_send_failed off=%u chunk=%u mtu=%u\n", (unsigned)off, (unsigned)n, (unsigned)mtu); return; }
  }
  uint32_t pk_send_us = (uint32_t)(esp_timer_get_time() - t);
  Serial.printf("# pk_sent chunk=%u mtu=%u us=%u\n", (unsigned)chunk, (unsigned)mtu, (unsigned)pk_send_us);

  // 3. wait for the full ciphertext
  uint32_t w0 = millis();
  while (connected && ctLen < CT_BYTES && !ctOverflow && millis() - w0 < 10000) delay(0);
  expectingCt = false;
  if (ctLen != CT_BYTES || ctOverflow) {
    sendResult('E', keygen_us, pk_send_us, 0, 0, 0);
    Serial.printf("# handshake_error ct_len=%u overflow=%d\n", (unsigned)ctLen, (int)ctOverflow);
    return;
  }
  uint32_t ct_rx_us = (uint32_t)(ctLastUs - ctFirstUs);

  // 4. decapsulate
  t = esp_timer_get_time();
  PQCLEAN_MLKEM768_CLEAN_crypto_kem_dec(ss, ct, sk);
  uint32_t decaps_us = (uint32_t)(esp_timer_get_time() - t);

  uint8_t h[32];
  sha3_256(h, ss, SS_BYTES);
  sendResult('O', keygen_us, pk_send_us, ct_rx_us, decaps_us, h[0]);

  // Serial after the result has been sent, so printing never lands inside a
  // measured interval.
  handshakes++;
  // BLE,n,cpu_mhz,mtu,interval_units,keygen_us,pk_send_us,ct_rx_us,decaps_us,ss_tag
  Serial.printf("BLE,%u,%u,%u,%u,%u,%u,%u,%u,%02x\n", (unsigned)handshakes,
                (unsigned)getCpuFrequencyMhz(), (unsigned)mtu, (unsigned)intervalUnits,
                (unsigned)keygen_us, (unsigned)pk_send_us, (unsigned)ct_rx_us,
                (unsigned)decaps_us, h[0]);
}

static void halt(const char *what) {
  Serial.printf("# FAILED %s\n", what);
  while (true) delay(1000);
}

void setup() {
  Serial.begin(115200);
  delay(1500);
  Serial.printf("# ble_mlkem board=esp32 cpu_mhz=%u pk=%u ct=%u\n",
                (unsigned)getCpuFrequencyMhz(), PK_BYTES, CT_BYTES);

  if (!BLE.begin("PQC-ESP32")) halt("BLE.begin");
  BLEServer server = BLE.createServer();
  if (!server) halt("createServer");

  server.onConnect([](BLEServer s, const BLEConnInfo &c) {
    connHandle = c.getHandle();
    mtu = c.getMTU();
    intervalUnits = c.getInterval();
    connected = true;
    Serial.printf("# connected mtu=%u interval_units=%u\n", (unsigned)mtu, (unsigned)intervalUnits);
  });
  server.onDisconnect([](BLEServer s, const BLEConnInfo &c, uint8_t reason) {
    connected = false;
    Serial.printf("# disconnected reason=0x%02x\n", reason);
    BLE.startAdvertising();
  });

  BLEService svc = server.createService(SVC_UUID);
  if (!svc) halt("createService");

  ctrlChr = svc.createCharacteristic(CTRL_UUID, BLEProperty::Write, BLEPermission::WriteOpen);
  pkChr = svc.createCharacteristic(PK_UUID, BLEProperty::Notify, BLEPermission::None);
  ctChr = svc.createCharacteristic(CT_UUID, BLEProperty::Write | BLEProperty::WriteNR, BLEPermission::WriteOpen);
  resChr = svc.createCharacteristic(RES_UUID, BLEProperty::Read | BLEProperty::Notify, BLEPermission::ReadOpen);
  if (!ctrlChr || !pkChr || !ctChr || !resChr) halt("createCharacteristic");

  auto onSt = [](BLECharacteristic &c, BLECharacteristic::NotifyStatus stt, uint32_t code) {
    notifyCode = code;
    notifyState = (stt == BLECharacteristic::SUCCESS_NOTIFY) ? 1 : -1;
  };
  pkChr.onStatus(onSt);
  resChr.onStatus(onSt);

  ctrlChr.onWrite([](BLECharacteristic c, const BLEConnInfo &conn) {
    size_t n = 0;
    const uint8_t *d = c.getValue(&n);
    // The MTU and interval can change after connecting; record them as they
    // are when the handshake starts.
    mtu = conn.getMTU();
    intervalUnits = conn.getInterval();
    if (n >= 1 && d[0] == 'K') startReq = true;
    else if (n >= 3 && d[0] == 'F') freqReq = (uint16_t)(d[1] | (d[2] << 8));
  });

  ctChr.onWrite([](BLECharacteristic c, const BLEConnInfo &conn) {
    if (!expectingCt) return;
    size_t n = 0;
    const uint8_t *d = c.getValue(&n);
    int64_t now = esp_timer_get_time();
    if (ctLen == 0) ctFirstUs = now;
    if (ctLen + n > CT_BYTES) { ctOverflow = true; return; }
    memcpy(ct + ctLen, d, n);
    ctLen += n;
    ctLastUs = now;
  });

  if (!server.start()) halt("server.start");
  BLEAdvertising adv = BLE.getAdvertising();
  adv.addServiceUUID(SVC_UUID);
  if (!adv.start()) halt("advertising");
  Serial.println("# ready");
}

void loop() {
  if (freqReq) {
    uint16_t f = freqReq;
    freqReq = 0;
    // 80 MHz is the floor that keeps the radio and APB clock unchanged.
    bool ok = (f == 80 || f == 160 || f == 240) && setCpuFrequencyMhz(f);
    Serial.printf("# cpu_mhz=%u set_%s\n", (unsigned)getCpuFrequencyMhz(), ok ? "ok" : "rejected");
  }
  if (startReq && connected) {
    startReq = false;
    runHandshake();
  }
  delay(1);
}

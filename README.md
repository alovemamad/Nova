# Nova VPN

**Nova VPN** is a modern, privacy-first Android VPN and local proxy client built with **Jetpack Compose**, **Material 3**, and a **Liquid Glass** visual system (`io.github.kyant0:backdrop`).

---

## Architecture & Traffic Flow

```
Device Apps ──▶ Android VpnService TUN (tun0)
                      │
                      ▼
        TUN Engine (sing-box libbox gVisor, automatic fallback to hev-socks5-tunnel)
                      │
                      ▼
        Local SOCKS5 Listener (127.0.0.1:10808)
                      │
                      ▼
   Multi-Route Core Chain (Aether · Psiphon · Tor/Lyrebird) ──▶ Internet
```

### Supported Route Chains & Protocols
- **Routes**: `SMART_AUTO`, `AETHER`, `AETHER_PSIPHON`, `AETHER_TOR`, `PSIPHON_ONLY`, `TOR_ONLY`
- **Aether Protocols**: `MASQUE`, `WIREGUARD`, `WARP_IN_MASQUE`, `WARP_IN_WARP`, `MASQUE_IN_MASQUE`
- **Obfuscation**: `AUTO`, `NONE`, `QUIC_TLS_CAMO`, `WIREGUARD_NOISE`, `AGGRESSIVE_DPI`
- **Operation Modes**: Full device **VPN** (`VpnService`) or **Proxy Only** (`SOCKS5` + `HTTP` local listeners, optional LAN sharing with explicit warning).

---

## Key Features
1. **One-Tap Connect & Live Telemetry**: Animated Glass Orb / Zed / Pill / Switch / Slide controls, uptime timer, live HTTPS ping, download/upload rates + sparkline, exit IP + country flag, and last drop reason.
2. **Smart Auto Mode**: Probes candidate routes with a real HTTPS 204 test and remembers the fastest working route per Wi-Fi SSID or mobile carrier.
3. **Smart Reconnection**: Deterministic `ReconnectStateMachine` with exponential backoff, offline pause (never wastes attempts offline), Wi-Fi ↔ Mobile handoff, and auto-switch from last good gateway to fresh scan after repeated failures.
4. **Pause & Resume**: Closes the TUN interface for instant bypass while keeping the underlying core alive.
5. **Kill Switch & Security**: `VpnService.Builder.setBlocking()`, Always-on VPN system settings shortcut, Biometric/PIN App Lock, and `FLAG_SECURE` + `excludeFromRecents` toggle.
6. **Profiles & QR Sharing**: Create, edit, duplicate, export (`novavpn://profile?data=...` URI, JSON, QR code bitmap), and import from clipboard or gallery QR image via zero-permission Android Photo Picker (`PickVisualMedia`).
7. **Built-in Diagnostics**: Exit IP & Geo check, DoH DNS leak test, IPv6 leak check, 3-endpoint latency (Cloudflare, Google DNS, Quad9), MTU probe, and one-tap **Fix Connection**.
8. **Data Usage & History**: Daily / 7-day / 30-day usage chart + recent session log.
9. **Bilingual UI (English + فارسی)**: Full RTL support, bundled `Vazirmatn` + `Plus Jakarta Sans` + `JetBrains Mono` fonts, and Persian/Latin numeral toggle.

---

## Building Native Core Binaries

Place upstream core sources under `native-src/` and run:

```bash
chmod +x scripts/build-native.sh
ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-native.sh
```

This cross-compiles `arm64-v8a`, `armeabi-v7a`, and `x86_64` shared libraries into `app/src/main/jniLibs/<abi>/`:
- `libaether.so`
- `libpsiphon.so`
- `liblyrebird.so`
- `libhev-socks5-tunnel.so`
- `app/libs/libbox.aar` (via `gomobile`)

> Note: When native binaries are omitted (for UI/logic testing), Nova VPN reports `MISSING_BINARY` cleanly without crashing or faking a tunnel.

---

## معرفی کوتاه (فارسی)

**نوا وی‌پی‌ان (Nova VPN)** یک کلاینت پیشرفته وی‌پی‌ان و پروکسی محلی برای اندروید است که با رابط کاربری شیشه‌ای (**Liquid Glass**)، پشتیبانی کامل از زبان **فارسی (راست‌به‌چپ)** و فونت **وزیرمتن** طراحی شده است.

### امکانات اصلی:
- اتصال تک‌لمسی با نشانگر زنده سرعت، نمودار لحظه‌ای، پینگ واقعی HTTPS و آی‌پی خروجی
- حالت **Smart Auto** برای تست خودکار مسیرها (Aether / Psiphon / Tor) و ذخیره بهترین مسیر برای هر شبکه
- دو موتور TUN: موتور پیش‌فرض `sing-box` (gVisor) همراه با جایگزینی خودکار `hev-socks5-tunnel` در صورت خطا
- توقف موقت (Pause) بدون بستن هسته، تونل‌زنی انتخابی برنامه‌ها (Split Tunneling) و قوانین مسیر/مسدودسازی
- عیب‌یابی داخلی (نشت DNS/IPv6، تست تاخیر، بررسی MTU و دکمه اصلاح خودکار اتصال)
- قفل برنامه (اثرانگشت / پین)، مخفی‌سازی از برنامه‌های اخیر، اشتراک‌گذاری پروفایل با QR Code و پشتیبان‌گیری JSON

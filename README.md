# Nova VPN

**Nova VPN** is a modern, privacy-first Android VPN and local proxy client built with **Jetpack Compose**, **Material 3**, and an animated **Liquid Glass** visual system.

---

## Architecture & Traffic Flow

```
Device Apps ──▶ Android VpnService TUN (tun0)
                      │
                      ▼
        TUN Engine (sing-box libbox gVisor, automatic fallback to hev-socks5-tunnel)
                      │
                      ▼
        Local Authenticated SOCKS5 Listener (127.0.0.1:<random-port>)
                      │
                      ▼
   Multi-Route Core Chain (Aether · Psiphon · Tor + Lyrebird) ──▶ Internet
```

### Supported Route Chains & Protocols
- **Routes**: `SMART_AUTO`, `AETHER`, `AETHER_PSIPHON`, `AETHER_TOR`, `PSIPHON_ONLY`, `TOR_ONLY`
  - **Tor requirement**: `TOR_ONLY` and `AETHER_TOR` require **both** `libtor.so` and `liblyrebird.so`.
  - **Psiphon requirement**: `PSIPHON_ONLY` and `AETHER_PSIPHON` require `libpsiphon.so`.
  - Missing required binaries fail cleanly with `MISSING_BINARY` without hanging or faking a tunnel.
- **Aether Protocols**: `MASQUE`, `WIREGUARD`, `WARP_IN_MASQUE`, `WARP_IN_WARP`, `MASQUE_IN_MASQUE`
- **Obfuscation**: `AUTO`, `NONE`, `QUIC_TLS_CAMO`, `WIREGUARD_NOISE`, `AGGRESSIVE_DPI`
- **Operation Modes**: Full device **VPN** (`VpnService`) or **Proxy Only** (`SOCKS5` + `HTTP` local listeners with per-install randomized ports `20000..44999` and per-session generated credentials; optional LAN sharing with explicit warning).

---

## Key Features
1. **One-Tap Connect & Live Telemetry**: Animated Glass Orb / Zed / Pill / Switch / Slide controls, uptime timer, live HTTPS ping, download/upload rates + sparkline (measuring outer UID tunnel traffic), exit IP + country flag, and last drop reason.
2. **Smart Auto Mode**: Probes candidate routes with a real HTTPS 204 test and remembers the fastest working route per Wi-Fi SSID or mobile carrier.
3. **Smart Reconnection**: Thread-safe `ReconnectStateMachine` with exponential backoff, offline pause (never wastes attempts offline), Wi-Fi ↔ Mobile handoff, and auto-switch from last good gateway to fresh scan after repeated failures.
4. **Pause & Resume**: Closes the TUN interface for instant bypass while keeping the underlying core alive (user is explicitly warned in the notification that app traffic bypasses the tunnel while paused).
5. **Kill Switch & Security**:
   - Retains the TUN interface during in-app reconnects (`keepTunOnReconnect`) to prevent transient traffic leaks.
   - Direct shortcut to Android **Always-on VPN + Block connections without VPN** (the only system-enforced device-wide kill switch on Android).
   - PBKDF2-HMAC-SHA256 (120k iterations) + Android Keystore AES-256-GCM App Lock with exponential lockout, Biometric unlock, and `FLAG_SECURE` + `excludeFromRecents` toggle.
6. **Profiles & QR Sharing**: Create, edit, duplicate, export (`novavpn://profile?data=...` URI, JSON, QR code bitmap), and import from clipboard, deep link (`novavpn://profile`), or gallery QR image via zero-permission Android Photo Picker (`PickVisualMedia`).
7. **Built-in Diagnostics**: Exit IP & Geo check, DoH DNS leak test, IPv6 leak check, 3-endpoint latency (Cloudflare, Google DNS, Quad9), MTU probe, and one-tap **Fix Connection**.
8. **Data Usage & History**: Daily / 7-day / 30-day usage chart + recent session log.
9. **Bilingual UI (English + فارسی)**: Full RTL support, live in-app locale switching, bundled `Vazirmatn` + `Plus Jakarta Sans` + `JetBrains Mono` fonts, and Persian/Latin numeral toggle.

---

## Building Native Core Binaries

Place upstream core sources under `native-src/` and run:

```bash
chmod +x scripts/build-native.sh
ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-native.sh
```

This cross-compiles `arm64-v8a`, `armeabi-v7a`, and `x86_64` artifacts:
- `app/src/main/jniLibs/<abi>/libaether.so` (Aether CLI proxy)
- `app/src/main/jniLibs/<abi>/libpsiphon.so` (Psiphon CLI wrapper)
- `app/src/main/jniLibs/<abi>/libtor.so` (Tor daemon CLI)
- `app/src/main/jniLibs/<abi>/liblyrebird.so` (Lyrebird pluggable transport spawned by Tor)
- `app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so` (JNI lib for `hev.htproxy.TProxyService`)
- `app/libs/libbox.aar` (sing-box gVisor TUN engine via `gomobile`)

> Note: When native binaries are omitted (for UI/logic testing), Nova VPN reports `MISSING_BINARY` cleanly within seconds without crashing or faking a tunnel.

---

## معرفی کوتاه (فارسی)

**نوا وی‌پی‌ان (Nova VPN)** یک کلاینت پیشرفته وی‌پی‌ان و پروکسی محلی برای اندروید است که با رابط کاربری شیشه‌ای (**Liquid Glass**)، پشتیبانی کامل از زبان **فارسی (راست‌به‌چپ)** و فونت **وزیرمتن** طراحی شده است.

### امکانات اصلی:
- اتصال تک‌لمسی با نشانگر زنده سرعت ترافیک تونل، نمودار لحظه‌ای، پینگ واقعی HTTPS و آی‌پی خروجی
- حالت **Smart Auto** برای تست خودکار مسیرها (Aether / Psiphon / Tor) و ذخیره بهترین مسیر برای هر شبکه
- پورت‌های تصادفی محلی با احراز هویت سشن‌محور برای SOCKS5 و HTTP
- دو موتور TUN: موتور پیش‌فرض `sing-box` (gVisor) همراه با جایگزینی خودکار `hev-socks5-tunnel` در صورت خطا
- نگهداری TUN هنگام اتصال مجدد + میان‌بر **Always-on VPN / Block connections without VPN** اندروید
- عیب‌یابی داخلی (نشت DNS/IPv6، تست تاخیر، بررسی MTU و دکمه اصلاح خودکار اتصال)

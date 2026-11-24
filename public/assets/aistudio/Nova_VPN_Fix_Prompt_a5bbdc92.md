# Nova VPN: Bug-Fix & Hardening Task

You are working on the existing Android project **Nova VPN** (Kotlin, Jetpack Compose, package `app.novavpn`, namespace `com.example`). Do NOT rewrite the app or redesign the UI. Apply the targeted fixes below, keep the current architecture, and keep everything compiling. After each fix, double-check that no other file still references removed or renamed symbols.

## Hard constraints
- Do not remove or modify the `signingConfigs` / `signingConfig` blocks in `app/build.gradle.kts` (required by AI Studio).
- Do not change the Liquid Glass UI, bilingual (EN/FA, RTL) behavior, or existing public behavior not mentioned here.
- Native binaries (`libaether.so`, `libpsiphon.so`, `liblyrebird.so`, `libhev-socks5-tunnel.so`, `libbox.aar`) are NOT bundled. The app must still fail cleanly with `MISSING_BINARY`, show the error in the UI, and stay fully usable (Disconnect works, retry works). Never fake a tunnel.
- Keep all existing unit tests passing and add tests where stated.

---

## P0: Critical fixes

### 1. Mutex deadlock in `NovaVpnService`
`startTunnel()` and `resumeTunnel()` run inside `lifecycleMutex.withLock { ... }` and call `stopTunnel()` inside that block, but `stopTunnel()` also does `lifecycleMutex.withLock`. kotlinx `Mutex` is not reentrant, so this deadlocks: the UI stays on CONNECTING forever and Disconnect stops working.
**Fix:** split into `stopTunnelLocked(...)` (no locking, assumes the caller holds the mutex) and a public `stopTunnel(...)` that acquires the lock and calls it. Inside `startTunnel` / `resumeTunnel` / reconnect paths, call only `stopTunnelLocked`. Audit every call path (`onRevoke`, `ACTION_STOP`, `handleReconnectDecision`, core-exit callback) for the same problem.
**Test:** add a coroutine test (or a small refactor that makes the lifecycle testable) proving that a failed start (e.g. MISSING_BINARY) ends in state ERROR/DISCONNECTED and a following stop request completes.

### 2. Self-cancellation in telemetry loop
When the ping-failure path hits `STOP_FAILED`, `stopTunnel()` is called from inside `telemetryJob` and then cancels `telemetryJob` (itself). Cleanup is cut off midway (state never becomes ERROR, the service never stops). Also `runCatching { suspendCall() }` swallows `CancellationException`.
**Fix:** never run stop/reconnect logic inside `telemetryJob`; launch it in a separate job (e.g. `serviceScope.launch`) and let the telemetry loop just end. Run the final cleanup in `withContext(NonCancellable)`. Replace `runCatching` around suspend calls with a helper that rethrows `CancellationException`.

### 3. Fake kill switch / traffic leaks
- `Builder.setBlocking()` only controls blocking I/O on the TUN fd. It is NOT a kill switch. Remove the claim from the code, settings text and README. Rename the setting so it does not mislead.
- Real protection: when a reconnect/restart happens (core died, ping failure, network switch), keep the TUN interface open (traffic is blackholed instead of leaking) and restart only the core process. Close the TUN only on a user disconnect or a final failure. When Pause is used, show a clear warning that traffic goes direct.
- Add a settings shortcut/dialog that opens Android's system VPN settings (`Settings.ACTION_VPN_SETTINGS`) and explains "Always-on VPN" + "Block connections without VPN" as the real kill switch.

### 4. sing-box (libbox) TUN integration
The current `PlatformInterface` Proxy returns `false/0/null` for every method (including `openTun`), and the config uses deprecated sing-box formats.
**Fix:**
- Implement a real `PlatformInterface` adapter: `openTun` must return the existing TUN fd (the one from `VpnService.Builder.establish()`), `protect(fd)` must call `VpnService.protect`, and the other methods must have sane implementations. Keep reflection so the project compiles without `libbox.aar`.
- Update `buildSingBoxConfig` to the current sing-box schema (route action rules like `{"action":"hijack-dns"}` instead of the `dns-out` outbound, `route.rules` with `sniff` actions instead of the inbound `sniff` field, no special `block`/`dns` outbounds, DNS servers in the current format). Escape all interpolated strings with `kotlinx.serialization.json` (build a `JsonObject` instead of string templates).
- Add a unit test that the generated config is valid JSON for DoH/DoT/PLAIN and with block/direct rules.

### 5. Native core command-line assumptions
Several CLI flags are guessed (`--bind --protocol --obfs --scan --upstream --direct` for Aether, `--tun-fd` for hev-socks5-tunnel; Psiphon `MobileLibrary/psi`; Lyrebird used as if it were Tor with a "bootstrapped 100%" log line).
**Fix:** move every core-specific flag, readiness-log pattern and config template behind a small `CoreAdapter` interface per core (Aether, Psiphon, Tor+Lyrebird, Hev) with a single place to change them, and document the assumed CLI contract at the top of each adapter. For hev-socks5-tunnel, use the JNI `TProxyStartService(configPath, fd)` path only and remove the invented `--tun-fd` binary fallback. For Tor, make the route require a real `tor` binary + Lyrebird as a PT (adjust `AetherCommandBuilder`, `CoreProcessManager` and `build-native.sh` accordingly), or clearly mark the Tor routes as unavailable when it is missing. Do not claim a working chain that cannot work.

---

## P1: Important fixes

### 6. Split tunneling INCLUDE mode is broken
`addDisallowedApplication(packageName)` is always called first, and Android forbids mixing allowed and disallowed apps on one `Builder` (the exception is swallowed by `runCatching`, so INCLUDE mode silently routes every app through the VPN).
**Fix:** in INCLUDE mode use only `addAllowedApplication` for the selected packages (and make sure the core processes still do not loop: protect their sockets via `VpnService.protect` or run the cores before the TUN exists and bind them to the underlying network). In EXCLUDE/DISABLED modes keep `addDisallowedApplication(packageName)`. Log failures instead of swallowing them, and show an error if INCLUDE has zero valid packages.

### 7. Network callback causes reconnect loops
`registerNetworkCallback` listens to all networks including the VPN itself, so `onLost/onAvailable` fire on every TUN open/close and on non-default networks.
**Fix:** use `registerDefaultNetworkCallback`, ignore networks with `TRANSPORT_VPN`, debounce events (about 1 s), and compute `currentNetworkKey()` from the underlying (non-VPN) network (use `ConnectivityManager.getAllNetworks()` filtered by `NOT_VPN`, or the callback's `NetworkCapabilities`). Make `ReconnectStateMachine` thread-safe (confine it to one dispatcher or guard with a Mutex) and add tests for Wi-Fi to mobile handoff and offline pause.

### 8. DNS broken with the hev fallback engine
DNS is set to `10.10.0.2`, which only sing-box intercepts. With hev there is no DNS.
**Fix:** when the active engine is hev, use hev's `mapdns` feature (or a plain DNS server through the SOCKS5 UDP associate) and make `buildVpnInterface` choose the DNS address based on the engine that will actually be used.

### 9. Unauthenticated local SOCKS5 / HTTP proxy
Any app on the device can connect to `127.0.0.1:10808`, and in LAN-share mode to `0.0.0.0`.
**Fix:** generate a random username/password per session (store in memory only), pass it to the cores and to the sing-box/hev outbound, and require it. For LAN share, keep the explicit warning and also require the credentials, showing them in the UI. Randomize the default local port per install instead of the fixed 10808 (still user-editable).

### 10. App-lock PIN
SHA-256 with a fixed prefix over a 4-digit PIN is trivially brute-forced and has no throttling.
**Fix:** use a per-install random salt + PBKDF2 (or similar, at least 100k iterations), store the salt/hash in the Android Keystore-backed storage (EncryptedFile / or encrypt via a Keystore AES key), add attempt counting with exponential lockout (e.g. 30 s after 5 failures), allow 6+ digit PINs, and migrate the old hash format transparently.

### 11. Manifest / backup / exported components
- Set `android:allowBackup="false"` and write real `backup_rules.xml` / `data_extraction_rules.xml` that exclude everything (profiles can contain proxy credentials).
- `NovaVpnWidgetProvider` is exported with a custom toggle action: remove the custom action from the exported intent-filter (keep only `APPWIDGET_UPDATE`) and drive the toggle through an immutable explicit `PendingIntent`, or verify the sender, so other apps cannot toggle the VPN.
- Add an intent-filter for `novavpn://profile` on `MainActivity` and handle the incoming data (ask for confirmation before importing a profile).
- Re-check `BootReceiver` for Android 12+ foreground-service-start restrictions (catch `ForegroundServiceStartNotAllowedException` and fall back to a notification asking the user to open the app).

### 12. Duplicate connect logic
`VpnController` and `NovaVpnService` both implement start/probe/route-selection logic. Make `NovaVpnService` the single owner for VPN mode, and keep `VpnController` as a thin command/state facade. If PROXY_ONLY mode needs the core without a TUN, extract the shared start/probe code into one class used by both.

---

## P2: Build / CI / cleanup

- The repo is missing `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`. Add them (Gradle wrapper matching `gradle-wrapper.properties`) so CI works.
- In `.github/workflows/android-ci.yml`: the final upload step uses `env.KEYSTORE_BASE64` which is only defined in the previous step, so it never runs. Fix with a job-level env or a step output. Add `chmod +x gradlew`. Remove the unneeded `setup-go` step.
- Remove unused plugins (`secrets`, `google-services`) and unused template files if nothing uses them (`com.example.ui.theme.*`, `GEMINI_API_KEY` comments, `metadata.json` Gemini capability). Keep the `signingConfigs`.
- Update `okhttp` from 4.10.0 to a current stable version.
- `CoreProcessManager`:
  - Fix the Psiphon readiness condition (operator precedence: `a && b || c`); add parentheses and use a stricter pattern.
  - Make `lastDetectedEndpoint` thread-safe (`AtomicReference` / `@Volatile`).
  - `isPortAvailable` should check only TCP unless the core needs UDP on that port.
- Stats: `TrafficStats.getUidRxBytes(myUid)` measures outer encrypted traffic of the app UID, not user data. Either label it as "tunnel traffic" or count bytes at the TUN engine/SOCKS layer.
- Move hard-coded UI strings written with `tr("EN","FA")` and literal `Text("...")` to `strings.xml` / `values-fa/strings.xml`, at least for the screens you touch.
- Add tests: service lifecycle (failed start, stop during connecting, pause/resume), sing-box config JSON, PIN hashing/lockout, split-tunnel builder logic (extract the builder configuration into a testable function).

---

## Definition of done
1. Project builds (`assembleDebug`) and all unit tests pass.
2. With no native binaries: tapping Connect shows a clear `MISSING_BINARY` error within seconds, the state returns to ERROR/DISCONNECTED, and Disconnect/Retry both work (no hang).
3. No code path takes `lifecycleMutex` twice, and no stop/reconnect logic runs inside the job it cancels.
4. README no longer claims `setBlocking()` is a kill switch and no longer claims a Tor/Psiphon chain works unless the required binaries exist.
5. Give me a short changelog listing every file changed and anything from this list you could not complete, with the reason.

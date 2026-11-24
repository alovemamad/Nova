# Nova VPN: Fix app crashing on launch

## Problem
After the latest changes, the app closes immediately when opened ("keeps stopping") and never reaches the UI. Fix it with targeted changes only. Do NOT redesign the UI, and do NOT touch the `signingConfigs` / `signingConfig` blocks in `app/build.gradle.kts`.

## Most likely root cause (please verify in the code)
`NovaVpnTheme` in `app/src/main/java/app/novavpn/ui/theme/NovaTheme.kt` now provides:

```kotlin
LocalContext provides localizedContext   // from baseContext.createConfigurationContext(config)
```

`createConfigurationContext()` returns a plain Context that is NOT an Activity and does NOT wrap the Activity. Composables inside the theme that call `rememberLauncherForActivityResult` (`OnboardingScreen`, `ProfilesScreen`) can no longer find the `ActivityResultRegistryOwner` through `LocalContext`, which throws `IllegalStateException: No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner` during the first composition. `OnboardingScreen` is the first screen after a fresh install, so the app dies at launch. Anything else inside the theme that casts `LocalContext.current` to an Activity (or searches the ContextWrapper chain) is affected too.

## Required fix
1. In `NovaTheme.kt`, keep the Activity in the Context chain. Replace the `createConfigurationContext` result with a `ContextWrapper` around the original context that only overrides `getResources()`:

```kotlin
private class LocaleContextWrapper(
    base: Context,
    private val localized: Context
) : ContextWrapper(base) {
    override fun getResources(): Resources = localized.resources
}

val localizedContext = remember(baseContext, targetLocale) {
    val config = Configuration(baseContext.resources.configuration).apply {
        setLocale(targetLocale)
        setLayoutDirection(targetLocale)
    }
    LocaleContextWrapper(baseContext, baseContext.createConfigurationContext(config))
}
```

   Keep `LocalConfiguration provides localizedContext.resources.configuration`, `LocalLayoutDirection`, and the in-app language switching behavior exactly as they work now (FA/EN/System, RTL).
2. Make sure `dynamicDarkColorScheme(...)` / `dynamicLightColorScheme(...)` and `findFragmentActivity()` still use the ORIGINAL `baseContext` (not the wrapper), and that `LocalHostActivity` is still provided for `AppLockGate` (biometric prompt).
3. Audit every `LocalContext.current` usage under `app/src/main/java` (`AppLockGate`, `HomeScreen`, `ProfilesScreen`, `SettingsModals`, `GlassTilt`, `OnboardingScreen`) and confirm none of them casts it to `Activity`/`FragmentActivity` directly. If any does, use `LocalHostActivity.current` or the ContextWrapper-aware helper instead.
4. Confirm `NovaVpnRoot` still calls `rememberLauncherForActivityResult` correctly after the change.

## Make startup failures visible instead of killing the app
- Add a `CoroutineExceptionHandler` to the long-lived scopes created at startup: `NovaVpnApplication.appScope`, `VpnController.scope`, and `NovaVpnViewModel`'s startup `viewModelScope.launch { ... }` block. The handler must log via `LogRepository.e(...)` and must not crash the process.
- Wrap `ServiceLocator.init(...)` in `NovaVpnApplication.onCreate` and `MainActivity.onCreate` in try/catch. On failure, log the full stack trace and show a minimal fallback screen that displays the exception class + message (selectable text) and a "Copy error" button, so the real error can be read on the device.
- In `NovaVpnViewModel.init`, wrap `checkAutoConnectRules()` and `ensurePerInstallRandomPorts()` in try/catch with logging.

## Also check for other launch-time crash sources
Review these once and fix anything that could throw on first launch (missing permission, null, wrong thread, bad resource id):
- `NovaVpnApplication.createNotificationChannels` and the string resources it uses (all keys must exist in both `values/strings.xml` and `values-fa/strings.xml`).
- Every `R.string.*` key used by the UI exists in both language files (the recent move from `tr("EN","FA")` to `stringResource` may have missed keys). Add a unit test that fails when a key is missing from either file.
- `rememberGlassTilt` (sensor access) must handle devices without the sensor.
- Manifest: every class named in `AndroidManifest.xml` exists, and the `novavpn://profile` intent-filter handling in `MainActivity.handleDeepLinkIntent` cannot throw on malformed input.

## Definition of done
1. A fresh install opens straight to Onboarding with no crash, in English, Persian, and System language.
2. Switching language in Settings updates text and RTL/LTR live without a crash, and file/QR pickers on the Profiles screen and the VPN/notification permission buttons in Onboarding still open.
3. App lock (PIN and biometric) still works.
4. `assembleDebug` and `assembleRelease` build, and all unit tests pass.
5. Reply with a short changelog (files changed, and what you confirmed was or was not the real cause).

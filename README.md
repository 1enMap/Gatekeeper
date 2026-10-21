# Gatekeeper: a per-app firewall for Android

Gatekeeper controls internet access for each app without root. Every installed app can be set to one of three modes:

| Mode | Behaviour |
|---|---|
| **Allowed** | Normal internet. |
| **Blocked** | No internet, ever. |
| **While open** | No internet in the background. The app gets internet only while it is on screen, plus a short grace period (30 s by default, 0–120 s per app) after you leave it or turn the screen off. |

For example, with WhatsApp set to **While open**, no messages arrive in the background, so senders see a single tick until you open WhatsApp.

Gatekeeper has **no `INTERNET` permission**, no analytics, no crash reporting and no third-party SDKs. Rules are stored on the device and excluded from cloud backup and device transfer.

## Building

Requirements: JDK 17 or newer and the Android SDK with platform 36 (Android Studio provides both).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For daily use, install the **release** build: it is minified and much smoother than the debug build (Compose debug builds are noticeably laggy).

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Both builds are signed with the test key in `keystore/`, so any build from this repo installs over the previous one and keeps your rules. Replace that key with your own private one before publishing anywhere.

If you build from the command line without Android Studio, create `local.properties` with `sdk.dir=/path/to/Android/Sdk` or set `ANDROID_HOME`.

Stack: Kotlin, Jetpack Compose (Material 3) in a single activity, Room for rules, DataStore for settings, and Coroutines/Flow. minSdk 29 (Android 10), targetSdk 36.

## How it works

1. **Local VPN (`vpn/GatekeeperVpnService.kt`).** The tunnel is built with `addAllowedApplication()` for blocked apps only, so only their traffic enters it. Every other app bypasses the VPN and uses the network as usual. The tunnel routes `0.0.0.0/0` and `::/0` (IPv4 and IPv6) and points DNS at addresses inside the tunnel. A reader thread (`TunnelDrainer`) reads packets and discards them.
   - **Empty blocked set:** Gatekeeper never calls `establish()`. A builder with zero allowed apps would route *every* app into the tunnel and cut off all internet, so the tunnel is closed and the service stays idle.
   - **Rebuilds:** changes to the blocked set are debounced by 300 ms. A new tunnel is established *before* the old file descriptor is closed, so the switch is seamless.
   - **Uninstalled apps:** a `NameNotFoundException` from `addAllowedApplication` skips that app and deletes its rule.
   - **Self-protection:** Gatekeeper never adds itself to the blocked set.
   - **`onRevoke()`:** if another VPN starts or you turn the VPN off in Settings, Gatekeeper stops cleanly, turns off the master switch and shows a notification.
2. **Engine (`engine/FirewallEngine.kt`).** The blocked set is every **Blocked** app plus every **While open** app that is not currently allowed. An app becomes allowed when it comes to the foreground. When it leaves, a grace timer starts, and when the timer fires the app is blocked again.
3. **Foreground detection.**
   - **Primary: `ForegroundAccessibilityService`.** It listens only to `typeWindowStateChanged` with `canRetrieveWindowContent="false"`, so it sees package names and nothing else. Events from System UI, all enabled keyboards, Gatekeeper itself, permission dialogs, toasts and other non-launchable overlays are ignored. This means opening the keyboard inside WhatsApp doesn't count as leaving WhatsApp.
   - **Fallback: `UsageStatsPoller`.** If you decline accessibility, Gatekeeper reads `UsageStatsManager.queryEvents` every 1.5 s. This mode reacts more slowly.
   - **Switching to Gatekeeper** counts as leaving the current app (Gatekeeper is not ignored as a foreground app, so checking its status doesn't keep a While open app online).
   - **Screen off:** turning the screen off counts as leaving the app and starts its grace timer. A wake lock, held for at most the grace period, makes sure the timer fires on time. Unlocking the phone restores the app you were in.

## Recent activity log

The home screen lists the latest allow/block decisions with timestamps (for example "WhatsApp · Left the app: blocked in 30 s"), so you can check While open behaviour without a computer. The log is kept in memory only and cleared when the app restarts.

## Permissions and why each is needed

| Permission | Why |
|---|---|
| *VPN consent* (system dialog) | Routes blocked apps' traffic into the local tunnel so it can be dropped. Nothing is forwarded anywhere. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | Keeps the firewall running, with the "Gatekeeper active · N apps blocked" notification. On Android 14+ the service uses the `systemExempted` type, which the [foreground service types documentation](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted) explicitly allows for VPN apps. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Fallback type, used only if a device refuses `systemExempted`. |
| `RECEIVE_BOOT_COMPLETED` | Restarts the firewall after a reboot, but only if the master switch was on. |
| `POST_NOTIFICATIONS` | Status notification, the "firewall stopped" alert, and the Allow / Block / While open prompt for newly installed apps. |
| `QUERY_ALL_PACKAGES` | A firewall needs to list every installed app. |
| `PACKAGE_USAGE_STATS` (Usage Access, optional) | Used only for the fallback foreground detection, if you decline accessibility. |
| Accessibility service (optional, recommended) | Detects which app is open so it can be allowed internet. No screen content is read. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets onboarding show the system dialog that stops Android from killing the firewall. |
| `WAKE_LOCK` | Holds the CPU awake for at most the grace period after the screen turns off, so While open apps are re-blocked on time. |
| `INTERNET` | **Not requested.** It is explicitly stripped from the merged manifest with `tools:node="remove"`. |

## Setup (also covered by the in-app onboarding)

1. **VPN consent.** Allow the VPN, and allow notifications on Android 13+.
2. **Accessibility.** Go to Settings → Accessibility → Downloaded/Installed apps → Gatekeeper → On.
   - If Android says the setting is *restricted* (common for sideloaded APKs on Android 13+), open App info → ⋮ → **Allow restricted settings**, then try again.
   - If you'd rather not use accessibility, choose **Use Usage Access instead**.
3. **Ignore battery optimisation.**
4. **Vivo / iQOO steps (Funtouch OS / OriginOS, e.g. Vivo V30 Pro):**
   - **Autostart:** iManager → App manager → Autostart manager → Gatekeeper **on**.
   - **Background power:** Settings → Battery → Background power consumption management → Gatekeeper → **Allow high background power consumption**.
   - **Lock in Recents:** open Recents and pull down on Gatekeeper's card, or tap its lock icon, so it shows a lock.
   - **Always-on VPN:** Settings → Network & internet (or *Other networks & connections*) → VPN → ⚙ next to Gatekeeper → **Always-on VPN on**.
   - ⚠️ **Do not** enable **"Block connections without VPN"**. Gatekeeper only tunnels blocked apps, so lockdown mode would cut off every other app. The home screen warns you if it's on.

## Known limitations

- Android allows only one VPN at a time, so Gatekeeper can't run alongside a real VPN.
- Apps in **While open** mode get no notifications and no incoming calls in the background. That is the point, but you should know it.
- Linked devices, such as WhatsApp Web or Desktop, can still receive messages and show double ticks.
- Apps that share a Linux UID with other apps are blocked or allowed together. The app detail screen tells you when this applies.
- **Cloned apps (Vivo App Clone / Dual apps)** run in a separate Android user. Gatekeeper's VPN only covers the main user, so the clone is not blocked. The clone also has the same package name as the original, so opening the clone counts as opening the original and gives the original internet. Don't use a clone to send test messages to the original on the same phone.

## Test checklist

Before testing WhatsApp, log out linked devices (WhatsApp → Linked devices) or quit them fully. A linked Mac or browser receives messages on its own and turns the ticks double even while the phone is blocked.

- [ ] Set WhatsApp to "While open" and have a second phone send a message: one tick. Open WhatsApp: the message arrives and the ticks turn double.
- [ ] Inside a "While open" app, open and close the keyboard: the app is not blocked.
- [ ] Send a message and leave immediately: it is still delivered within the grace period.
- [ ] Turn the screen off: the app is re-blocked after the grace period.
- [ ] Set every app to Allowed: all internet still works (the empty-set edge case).
- [ ] Reboot: the firewall comes back on with the same rules.
- [ ] Uninstall an app that has a rule: no crash, and the rule is cleaned up.
- [ ] Start another VPN app: `onRevoke` is handled and a notification is shown.
- [ ] Leave the phone idle overnight on Vivo: the service is still running in the morning.

## Project layout

```
app/src/main/java/app/gatekeeper/
  GatekeeperApp.kt          Application; owns settings, rules repository and engine
  FirewallController.kt     Master switch: start/stop the VPN service
  Notifications.kt          Channels, status, "stopped" and "new app" notifications
  data/                     Room (AppRule, RuleDao, AppDatabase), DataStore settings, installed apps
  engine/FirewallEngine.kt  Rules + foreground app → blocked set; grace timers
  foreground/               Accessibility service, UsageStats fallback, ignore filter
  vpn/                      VpnService and the packet-dropping drainer thread
  receivers/                Boot, package removed, new-app notification actions
  tile/                     Quick Settings tile
  ui/                       Compose screens: home, app list, app detail, onboarding, settings, about
```

## Out of scope for v1

- Separate Wi-Fi and mobile-data rules
- Connection log (requires packet and DNS parsing)
- Schedules
- Import and export of rules

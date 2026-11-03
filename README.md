# Gatekeeper: a per-app firewall for Android

Gatekeeper controls internet access for each app without root. Every installed app can be set to one of three modes:

| Mode | Behaviour |
|---|---|
| **Allowed** | Normal internet. |
| **Blocked** | No internet, ever. |
| **While open** | No internet in the background. The app gets internet only while it is on screen, plus a short grace period (30 s by default, 0–120 s per app) after you leave it or turn the screen off. |

For example, with WhatsApp set to **While open**, no messages arrive in the background, so senders see a single tick until you open WhatsApp.

Gatekeeper never contacts any server of its own: no analytics, no crash reporting, no update checks and no third-party SDKs. Rules and logs are stored on the device and excluded from cloud backup and device transfer.

It holds the `INTERNET` permission for two features you turn on by hand: the **Live monitor**, which relays other apps' traffic so you can see where it goes, and the experimental **Tor mode**, which relays it through Tor. In normal mode Gatekeeper relays nothing: it only drops the packets of blocked apps.

## Install

Download the APK from the [latest release](https://github.com/1enMap/Gatekeeper/releases/latest) and open it on your phone (Android 10 or newer).

> [!IMPORTANT]
> **Android may flag Gatekeeper as a potentially risky app.** Google Play Protect may warn about or block the install, and Android may ask for extra confirmation or verification before you can turn on its accessibility service.
>
> This happens because Gatekeeper is installed from outside the Play Store and, like every per-app firewall that works without root, it combines a **VPN** with an **accessibility service**. Malware also abuses accessibility, so Android treats any sideloaded app that requests it as a potential risk, regardless of what the app actually does. The warning is about the *kind* of permission, not a detection of anything harmful in Gatekeeper.
>
> - If Play Protect shows **"Unsafe app blocked"**, tap **More details → Install anyway**.
> - If it blocks the install outright ("this app can request access to sensitive data"), install it from a computer with `adb install Gatekeeper-v1.0.apk`, or build it yourself (see [Building](#building)).
> - The accessibility service is **optional**. Without it, choose **Use Usage Access instead** during setup; "While open" then reacts within about 1.5 s instead of instantly.

### Why the permissions are safe to grant

Gatekeeper asks for powerful permissions, so here is exactly what it does with them. Every point can be checked in the source code.

- **Accessibility is used for one thing: the name of the app on screen.** The service subscribes only to window-change events and declares `canRetrieveWindowContent="false"` ([`accessibility_service.xml`](app/src/main/res/xml/accessibility_service.xml)), so Android never gives it the text, buttons or anything else on your screen. It never performs taps, gestures or other actions ([`ForegroundAccessibilityService.kt`](app/src/main/java/app/gatekeeper/foreground/ForegroundAccessibilityService.kt)).
- **The VPN is local.** Nothing is sent to a server. In normal mode, blocked apps' packets are simply discarded on the phone ([`TunnelDrainer.kt`](app/src/main/java/app/gatekeeper/vpn/TunnelDrainer.kt)).
- **No data leaves your phone.** There are no analytics, crash reporters, ads, accounts, update checks or third-party SDKs. Gatekeeper never opens a connection of its own; `INTERNET` is used only to relay other apps' traffic for features you switch on yourself (Live monitor, Tor, tracker blocking).
- **No camera, microphone, location, contacts, SMS or storage permissions.** The camera and mic alerts use Android's public "in use" signals, not the sensors themselves.
- **Rules and logs stay on the device** and are excluded from cloud backup and device transfer.
- **Every permission is explained** in [Permissions and why each is needed](#permissions-and-why-each-is-needed).

### Verify your download

Release APKs are signed with the author's key. Its SHA-256 certificate fingerprint is:

```
08:CE:6E:B0:0B:10:04:A7:5C:5A:48:40:C5:D8:1C:6C:4E:3C:58:E8:03:EB:E4:7E:C3:18:AB:8F:2F:BF:42:91
```

Check it with `apksigner verify --print-certs Gatekeeper-v1.0.apk`, or with any APK inspector app. An APK with a different fingerprint was not built by the author: don't install it. You can also skip the download entirely and build the app from this source code yourself.

## Building

Requirements: JDK 17 or newer and the Android SDK with platform 37.1 (Android Studio provides both).

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

Without further setup, both builds are signed with your machine's Android debug key. To sign release builds with your own key, create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=/path/to/your-release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

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

## The app at a glance

A bottom bar with five tabs:
- **Home:** a protection score (0–100, with a one-tap fix for every missing point), the firewall switch, alerts, status tiles (blocked connections, trackers, threats, camera & mic, Tor, live monitor) and the Tor controls.
- **Apps:** every app's internet mode, Tor and tracker badges, and a live-activity button.
- **Privacy:** camera, microphone and location status and switches, the camera & microphone timeline, and who can access what.
- **Activity:** the live monitor, connection log, data usage, security audit and recent events.
- **Settings.**

The look is Gatekeeper's own fixed theme, "Night watch": deep ink surfaces, a mint "protected" accent, amber for things worth a look, coral for problems and violet for Tor, with a matching light theme.

## Privacy tab (camera, microphone, location)

- **Switches.** Android only lets the system turn camera, microphone or location off for every app, so each tile opens the exact system switch. The quickest route is the *Camera access* and *Microphone access* Quick Settings tiles.
- **Camera & microphone timeline (sensor watch).** Gatekeeper notes every time the camera or microphone switches on and off. Android doesn't tell ordinary apps *which* app is using them, so each entry records the app that was on screen at the time.
  - **Alerts:** use while the screen is off, or with no app open, is flagged and (optionally) announced with a notification. Face unlock on the lock screen isn't flagged.
  - **No permissions:** Gatekeeper holds no camera, microphone or location permission. It only listens to Android's "camera available" and "recording started" signals.
  - **Storage:** history is kept on the phone for 30 days.
- **Who can access.** For camera, microphone, location (including "Always"), contacts, SMS and call logs: which of your apps have access, and whether they can go online. Tap one to change it in Android's settings.

## Privacy checkup (security audit, data usage, connection log)

The home screen's **Privacy checkup** card leads to three tools. None of them use the network.

- **Security audit** runs offline and scores every app *Needs a look* / *Worth checking* / *Looks fine*, explaining each finding:
  - **Special powers** that real Android malware abuses: accessibility access, notification access, device admin.
  - **Sensitive permissions** it holds (location, background location, SMS, microphone, call logs, contacts…), weighed by whether the app can go online at all.
  - **Where it came from:** app store vs. APK, and test-signed (often modified) builds.
  - **Look-alikes** of popular apps, such as a "WhatsApp" that isn't `com.whatsapp`, or WhatsApp/Instagram mods.
  - **Background data use** over the last 7 days.

  A flag means "have a look", not "this is malware". Each app's detail screen lists the findings and links to its Android permission settings.
- **Data usage** shows Android's own per-app counters (the same data as Settings → Data usage), split into *background* vs *on screen* and Wi-Fi vs mobile, for 24 hours, 7 days or 30 days. Needs Usage Access.
- **Connection log** lists every connection a blocked app tried to make. That includes While open apps in the background, because their packets are dropped inside Gatekeeper's tunnel. Grouped by app or as a timeline, with known ad, analytics and crash-reporting servers labelled from a small built-in list. **Night watch** summarises what blocked apps tried between midnight and 7 am. Entries are kept on the phone for 7 days; recording can be switched off in Settings.
  - With **Private DNS in strict mode** (e.g. `dns.adguard.com`), Android encrypts look-ups even inside the tunnel, so most entries show an IP address and port instead of a name. Set Private DNS to *Automatic* to see names.
- **Quarantine:** an app's detail screen has a *Quarantine* button that blocks it, so you can watch in the log what it tries to reach.

## Live monitor (see where every app connects)

Tap **Live monitor** on the home screen, or the **ⓘ** button next to any app, then **Start**.

**What changes while the monitor runs:**
- The VPN switches to a second mode: every app except Gatekeeper is routed into the tunnel.
- Blocked apps are still dropped.
- Everything else is relayed by a small user-space TCP/UDP forwarder (`monitor/ForwardingEngine.kt`). It completes each app's TCP handshake itself and copies the byte stream over a real socket that is excluded from the VPN.

**What you see, live, for each connection:**
- the app;
- the server name, from the TLS ClientHello (SNI), the HTTP `Host` header or the relayed DNS answers;
- the address and port;
- bytes up and down;
- whether it's still open.

You can view by app or as a list of connections, filter to open connections or trackers, and block an app from the list. Finished connections are also written to the Connection log, marked *let through*.

**Behaviour to know about:**
- **Only metadata is read.** That means server names and byte counts, never message or page content. HTTPS stays encrypted end to end.
- **QUIC (UDP 443) is stopped.** QUIC hides server names, so apps fall back to normal TCP connections that can be named.
- **Ping (ICMP) doesn't work** while monitoring.
- **It's on demand and never persisted.** It stops when you tap Stop, when the firewall stops, or after a restart. It costs more battery than normal mode.
- **It only works when Gatekeeper is the active VPN**, like everything else in the app.

When nothing is being relayed, the forwarder sleeps until a packet or a rule change arrives (no periodic wake-ups), so the relay features cost battery only while apps are actually using them.

The forwarder has JVM tests in `app/src/test/java/app/gatekeeper/monitor/` that drive it with hand-built packets against real local servers:
- TCP handshake and request/response;
- a 2 MB download with a lost segment, which must be retransmitted;
- a 1 MB upload;
- IPv6 packets;
- UDP;
- blocked apps;
- reset on refused and unknown connections;
- SNI extraction.

Every packet the engine produces is checksum-verified.

## Tor mode (experimental, per app)

Turn on **Tor mode** on the home screen, then choose which apps use it: **Apps → app → Through Tor**. Those apps' traffic goes through the [Tor network](https://www.torproject.org/); every other app stays direct, as if Gatekeeper weren't there. Tor runs inside Gatekeeper (Guardian Project's [tor-android](https://github.com/guardianproject/tor-android), the same Tor that Orbot uses), as a **client only**: your phone never relays anyone else's traffic. Tor itself only starts while at least one app uses it.

**How it works:** only apps that need Gatekeeper's relay are put in the tunnel (Tor apps, tracker-blocking apps, and Blocked / While open apps so their switching never rebuilds the tunnel). `monitor/ForwardingEngine.kt` opens each Tor app's connection through Tor's local SOCKS port; the app's SYN-ACK is sent only after Tor has opened the stream, so a failed circuit looks to the app like a refused connection.

- **Per-app circuits.** The SOCKS username is the app's package name. Tor keeps streams with different usernames on different circuits (`IsolateSOCKSAuth`), so traffic from two apps can't easily be linked by a single exit.
- **New identity** bumps a counter in the SOCKS password and sends `NEWNYM`: open Tor connections are reset and every Tor app reconnects on fresh circuits. Direct apps are unaffected.
- **DNS** of Tor apps (UDP 53, to any server) is answered by Tor's resolver (`DNSPort`), so lookups don't leak. `.onion` names resolve to placeholder addresses that Tor maps back.
- **Other UDP from Tor apps is dropped**, because Tor carries only TCP. QUIC falls back to TCP; voice/video calls don't work.
- **Fails closed, per app.** Until Tor reports 100 % bootstrapped, a Tor app's new connections are refused and its DNS gets no answer; nothing of a Tor app is ever relayed directly. If Tor fails, Tor apps stay offline until you retry or switch them back to direct. Connections Android can't attribute to an app are sent through Tor whenever any app uses Tor.
- **Turning Tor mode off** (home screen) sends every app direct again; the per-app choices are kept for next time.

**Limits (shown in the app too):**
- Tor hides your IP address, not your identity: log into an account and that site knows it's you.
- **Payment and banking (UPI) apps** expect an Indian connection and usually fail or lock you out over Tor. The app warns if you pick one. Register or re-verify accounts (e.g. WhatsApp) on a direct connection.
- It's slow, and some sites block or CAPTCHA Tor exits.
- While the phone boots, before Gatekeeper's VPN is up, Tor apps can go online directly for a few seconds. (Always-on VPN with "Block connections without VPN" would close that gap, but it also cuts off every direct app, so it can't be used with per-app routing.)
- Connections an app opened *before* you switched it to Tor may continue until they close; Android normally ends them when the app joins the VPN. Restart the app to be sure.
- Your mobile network or Wi-Fi can see that you use Tor (not what for). Bridges aren't supported yet.
- Not audited. Don't rely on it where your safety depends on anonymity; use Tor Browser or Tails for that.

Tor adds roughly 7–8 MB per CPU architecture to the APK.

## Tracker blocking (per app)

**Apps → app → Block trackers** (or **Settings → Tracker blocking → Block in all my apps**) stops an app from reaching known advertising, analytics and crash-reporting servers while the app itself keeps working. It's off by default.

- **DNS:** a tracking app's lookup of a tracker server gets an NXDOMAIN ("no such domain") answer and is never sent anywhere.
- **Connections:** a tracking app's TCP stream is held inside Gatekeeper until its server name is known (TLS SNI or HTTP `Host`, reassembled across segments); a tracker's connection is reset before a single byte reaches it. Addresses already known to belong to trackers are refused without connecting. QUIC is stopped so apps fall back to TCP, where the name is visible.
- **The list** (`insights/TrackerDomains.kt`, about 100 domains plus all their subdomains) is built in and only changes with app updates: Gatekeeper never downloads anything. Domains apps need to work, such as Firebase Cloud Messaging (push notifications), are deliberately not on it.
- **If an app breaks** (for example "Log in with Facebook" uses `graph.facebook.com`), turn tracker blocking off for that app.
- Blocked trackers show in the live view ("tracker blocked") and the Connection log.
- Encrypted Client Hello (ECH) hides server names; as it spreads, connection-level blocking catches less, while DNS-level blocking keeps working.

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
| `PACKAGE_USAGE_STATS` (Usage Access, optional) | Fallback foreground detection if you decline accessibility, and the per-app numbers on the Data usage screen. |
| Accessibility service (optional, recommended) | Detects which app is open so it can be allowed internet. No screen content is read. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets onboarding show the system dialog that stops Android from killing the firewall. |
| `ACCESS_NETWORK_STATE` | Reads whether Private DNS is on, to explain what the connection log can show. Grants no network access. |
| `WAKE_LOCK` | Holds the CPU awake for at most the grace period after the screen turns off, so While open apps are re-blocked on time. |
| `INTERNET` | Used only to relay other apps' traffic: while you run the Live monitor, for apps you send through Tor (and by Tor itself), and for apps with tracker blocking. Gatekeeper never opens connections of its own. |

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

## Screenshot tests

`app/src/test/java/app/gatekeeper/ui/ScreenshotTest.kt` renders the main screens on the JVM (Robolectric + Roborazzi, test-only dependencies). Normally it just checks the screens compose without crashing; to write PNGs to `app/build/screenshots/` for design review:

```bash
./gradlew testDebugUnitTest --tests 'app.gatekeeper.ui.ScreenshotTest' -Pscreenshots
```

## Project layout

```
app/src/main/java/app/gatekeeper/
  GatekeeperApp.kt          Application; owns settings, rules repository and engine
  FirewallController.kt     Master switch: start/stop the VPN service
  Notifications.kt          Channels, status, "stopped" and "new app" notifications
  data/                     Room (AppRule, RuleDao, AppDatabase), DataStore settings, installed apps
  engine/FirewallEngine.kt  Rules + foreground app → blocked set; grace timers
  insights/                 Security audit, data usage, packet header parser, connection observer, tracker list
  monitor/                  Live monitor: TCP/UDP forwarding engine, packet builder, SNI/DNS sniffing
  privacy/                  Sensor watch: camera and microphone use, with alerts
  tor/                      Embedded Tor (tor-android) controller
  foreground/               Accessibility service, UsageStats fallback, ignore filter
  vpn/                      VpnService and the packet-dropping drainer thread
  receivers/                Boot, package removed, new-app notification actions
  tile/                     Quick Settings tile
  ui/                       Compose screens: home, app list, app detail, onboarding, settings, about
```

## Out of scope for v1

- Separate Wi-Fi and mobile-data rules
- Schedules
- Import and export of rules

## License

Gatekeeper is free to use, study, modify and share for **non-commercial purposes** under the [PolyForm Noncommercial License 1.0.0](LICENSE.md). Selling it, or using it in a commercial product or service, is not permitted without written permission from the author.

Tor mode bundles [tor-android](https://github.com/guardianproject/tor-android) and [jtorctl](https://github.com/guardianproject/jtorctl) from the Guardian Project, which keep their own BSD licenses.

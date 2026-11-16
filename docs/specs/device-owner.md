# Managed mode (Device Owner) & the strategy switch

**Status: implemented, not yet device-tested.** Compiles and is CI-built, but Device Owner APIs
can't run in CI or on an un-provisioned device, so treat runtime behaviour as unverified until
tried on a real phone. Code: `admin/FocusFenceAdminReceiver.kt`, `admin/DeviceAdmin.kt`,
`admin/AppSuspension.kt`, `data/StrategyStore.kt`, `DeviceOwnerActivity`. Integrity keys added in
`monitor/Monitor.kt`.

## Stance: detect and notify, not block

We deliberately do **not** call `setUninstallBlocked`. Removing the app, or removing managed mode,
stays possible — behind friction, never a wall, and never a brick. What managed mode adds is that
every admin lifecycle event (`admin_enabled`, `admin_disable_requested`, `admin_disabled`) and
every policy change is written to the activity log as an urgent integrity record, and the
integrity trail now tracks `device_owner`, `admin_active` and `network_logging`. A partner (once
sync exists) sees protection drop; offline, the local record still shows it. `onDisableRequested`
returns a warning string — friction, not prevention.

## What Device Owner is used for

Three capabilities, all device-owner-only, wrapped in `DeviceAdmin` so they fail safe (no-op and
return false) when not provisioned:

1. **Private DNS lockdown** (`setGlobalPrivateDnsModeSpecifiedHost`, API 29+) — the strategy
   switch below.
2. **Network logging** (`setNetworkLoggingEnabled`) — package-attributed DNS, filling the
   "which app made this lookup" gap the VPN can't.
3. **Package suspension** (`setPackagesSuspended`) — OS-level app blocking, stronger than the
   accessibility home-bounce. `AppSuspension.reconcile` keeps the suspended set in step with the
   blocked-apps set; unsuspending only happens for apps removed from the set (the guarded
   `UNBLOCK_APP` action) or when the feature is turned off.

Not blocking uninstall means we skip the one thing Device Owner is most often used for, and keep
only the parts that serve detection, notification, and the strategy switch.

## The strategy switch

The single-VPN-slot constraint: to run another VPN, Focus Fence's own `VpnService` must give up the
slot. `StrategyStore.Strategy`:

- **LOCAL_DNS_VPN** (default): the tunnel does everything — full filtering + the local detection log.
- **PRIVATE_DNS**: stop the tunnel, set a locked Private DNS host; the slot is now free for another
  VPN and category filtering continues at the DNS layer. Switching back clears Private DNS and
  restarts the tunnel. Gated by the `SWITCH_STRATEGY` friction action.

Tradeoffs, surfaced in the UI:
- **Whose rules.** Private DNS points at a DoT hostname. The default is a public family-filter
  resolver (`family.cloudflare-dns.com` — malware + adult), i.e. category-level only. The owner's
  *custom* rules in this mode need a hosted DoT endpoint (the deferred backend).
- **The log.** In Private-DNS mode the phone no longer sees queries, so network logging (device
  owner) is what keeps the per-app detection record. That pairing is the point.

HTTPS interception is intentionally not a strategy: a user-installed CA isn't trusted by apps
targeting API 24+, and pinning/QUIC/ECH defeat it, so it would only work for a controlled browser.

## Provisioning (friction, not a button)

Not a tap by design. On a device with no added accounts, with USB or wireless debugging:

```
adb shell dpm set-device-owner app.focusfence/.admin.FocusFenceAdminReceiver
```

The in-app screen shows this command and the live status. A Shizuku-assisted flow (the Déchaîner
pattern) can run the same `dpm` call without a computer; it's a future addition. Device Owner
can't be set if other users/accounts exist, which is itself the friction.

## Recovery

Because we never block uninstall and `DeviceAdmin.relinquish` clears policies before
`clearDeviceOwnerApp`, there's no lockout path. Removing managed mode is the `REMOVE_PROTECTION`
guarded action (default a timed delay; can be raised to a partner PIN). Factory reset remains the
ultimate fallback. No recovery code is required precisely because nothing here is irreversible.

## Open items

- Not yet tested on a provisioned device; OEM behaviour around Private DNS lockdown and
  suspension varies.
- Custom-rule Private DNS needs the backend's DoT endpoint.
- Shizuku provisioning not yet built.

## VPN and DNS together

Strict Private DNS and our DNS VPN can't both filter at once: with Private DNS in
hostname mode, Android sends DoT straight to that host, outside the tunnel, and
the filter sees nothing (the health detector reports this as `bypassed`). So the
two run as a hand-off, not side by side:

- **Always-on VPN** (`setAlwaysOnVpnPackage`, never lockdown): the OS starts the
  tunnel at boot and keeps it up. Lockdown is avoided because the tunnel routes
  only DNS; lockdown would cut all traffic whenever it restarts.
- **Private DNS fallback** (`DnsFallback`): whenever the VPN goes down (turned
  off in-app, revoked, another VPN took the slot, failed to start), Private DNS
  is locked to the fallback host; when the VPN starts, our fallback is undone.
  A flag records that *we* set it, so a user's own setting is never cleared.
- **Settings lock** (`DISALLOW_CONFIG_VPN`, `DISALLOW_CONFIG_PRIVATE_DNS`):
  stops flipping either from system Settings. Turning it off in-app goes through
  the `LOOSEN_MANAGED` friction guard; relinquishing ownership clears it.

## Health detection

`dns/VpnHealth` (pure, tested) classifies the running filter from relay outcomes,
query arrivals, screen state and Private DNS mode. A change writes an urgent
`svc vpn health` record, rewrites the ongoing notification, and posts a separate
alert while there's a real problem. Unrequested stops (`revoked`,
`establish_failed`) post a "Protection stopped" alert. Evaluation rides the relay
path (throttled to 5 s) and Monitor's existing battery/screen broadcasts — no
timers or wakeups.

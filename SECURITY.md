# Security

Do not open a public issue with a Cloudflare tunnel token, Victron Instant Readout key, remote-control secret, or device log that contains any of those. Use the bug template's no-secrets checkbox, or GitHub's private advisory form for a leak.

This app stores those values on the phone. The named-tunnel token, Instant Readout keys, charger schedule, and remote-control secret are kept in device-protected storage so a reboot can restore the tunnel, live watts, daily window, and `/charger` page before anyone unlocks the phone. Android cloud backup and device transfer exclude the key store, named-tunnel token, remote-control secret, charger MAC, and diagnostics log. The `/charger` and `/voltage` HTTP surfaces are shared-secret protected; leave remote control off if the tunnel hostname is public and you do not need it.

Report a vulnerability via GitHub's private advisory form on this repository.

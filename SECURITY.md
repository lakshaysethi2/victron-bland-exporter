# Security

Do not open a public issue with a Cloudflare tunnel token, Victron Instant Readout key, remote-control secret, or device log that contains any of those.

This app stores those values on the phone. The `/charger` and `/voltage` HTTP surfaces are shared-secret protected; leave remote control off if the tunnel hostname is public and you do not need it.

Report a vulnerability via GitHub's private advisory form on this repository.

# Security and privacy

The offline edition has no account, subscription, telemetry, update or remote configuration endpoint. The UI uses an ephemeral HTTP server bound to `127.0.0.1`; a random per-process token protects its local API.

Please report defects that allow another local process or remote host to control the injector, alter the embedded components without detection by the build workflow, or obtain sensitive paths or process command lines.

Do not submit reports asking for third-party anti-cheat bypasses. Reproducible compatibility reports from a self-owned or explicitly authorized test environment are welcome.

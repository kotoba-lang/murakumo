# Murakumo Bot workspaces

Private execution service for the authenticated Itonami web app. Only a Cloudflare service binding can reach it; it has no public HTTP route. The caller supplies the server-verified owner, never a client/model-selected workspace identity. SHA-256(owner) selects one container and shared shell session for that owner's Bot team. Different owners have different containers.

Internet access is disabled. No OAuth tokens, operator credentials, host filesystem or fleet sockets are mounted. External services remain behind the application's owner-scoped plugin broker. Commands time out after 20 seconds; outputs are bounded. Ten containers maximum, sleeping after ten minutes idle. Container disks and shell state are ephemeral: persistent deliverables and SOUL.md live in the application's owner-scoped storage. This is not a persistent browser-login service or a fleet node shell.

Build with `npx wrangler deploy --dry-run`; deploy with `npx wrangler deploy`. The matching SDK and container versions are pinned together. Caller binding: `BOT_SANDBOX` -> `murakumo-bot-workspaces`.

## Shared browser

`/browser` uses Cloudflare Browser Run, separately from the offline command container.
A private owner-scoped BrowserTeam Durable Object serializes browser and command
operations. The browser shares one context across that owner's Bots. HTTPS navigation,
1280x800 JPEG screenshots, coordinate clicks, text input, selected keys and scrolling
are supported. Cookie, localStorage and IndexedDB state are checkpointed in private
Durable Object storage after operations, and restored after browser shutdown.
SessionStorage, downloads, extensions, device-bound passkeys and arbitrary desktop
applications are not persisted/provided. Sites may still require login again.

The shell cannot access this storage or browser binding. Browser state is never
returned through the public API; screenshots and visible text are owner-authenticated.
The browser has external HTTPS access; the shell remains offline. URL validation
rejects literal IPs, credentials in URLs and local/internal names; this is not a
comprehensive independent network isolation audit. Bot click/type/key/scroll actions
use the app's explicit approval card. Manual owner controls operate directly.
Revisions reject stale actions; a per-owner allowance bounds operations to 120/hour.
`close` checkpoints and stops the browser; `reset` removes its stored login state.
Use `CHROME_PATH=/path/to/chrome npm test` for actual browser isolation/persistence checks.

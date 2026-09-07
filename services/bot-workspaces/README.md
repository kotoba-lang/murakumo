# Murakumo Bot workspaces

Private execution service for the authenticated Itonami web app. Only a Cloudflare service binding can reach it; it has no public HTTP route. The caller supplies the server-verified owner, never a client/model-selected workspace identity. SHA-256(owner) selects one container and shared shell session for that owner's Bot team. Different owners have different containers.

Internet access is disabled. No OAuth tokens, operator credentials, host filesystem or fleet sockets are mounted. External services remain behind the application's owner-scoped plugin broker. Commands time out after 20 seconds; outputs are bounded. Ten containers maximum, sleeping after ten minutes idle. Container disks and shell state are ephemeral: persistent deliverables and SOUL.md live in the application's owner-scoped storage. This is not a persistent browser-login service or a fleet node shell.

Build with `npx wrangler deploy --dry-run`; deploy with `npx wrangler deploy`. The matching SDK and container versions are pinned together. Caller binding: `BOT_SANDBOX` -> `murakumo-bot-workspaces`.

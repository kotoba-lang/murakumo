// QA harness: load real pages in a headless browser, collect JS/console/network
// errors, check key elements + link health, and hit the live APIs. Emits a
// structured report the react loop consumes.
import { chromium } from 'playwright-core';

const SHELL = process.env.PW_SHELL ||
  '/Users/junkawasaki/Library/Caches/ms-playwright/chromium_headless_shell-1217/chrome-headless-shell-mac-arm64/chrome-headless-shell';

const PAGES = [
  { name: 'itonami.cloud (cockpit)', url: 'https://itonami.cloud/',
    must: ['Compute', '#compute', 'Open Business Registry'],
    navAnchors: ['#compute', '#open-business', '#proof'] },
  { name: 'app.itonami.cloud/itonami (Compute console)', url: 'https://app.itonami.cloud/itonami',
    must: ['murakumo Compute', 'ISCO', 'itonami.cloud'],
    links: ['https://itonami.cloud/', '/join/browser', '/itonami/verticals'] },
  { name: 'app.itonami.cloud/join/browser (worker)', url: 'https://app.itonami.cloud/join/browser',
    must: ['叢雲に加わる', 'did:key', 'relay.gftd.ai'], clickJoin: true },
];

// Full API surface. `assert` is an optional predicate over the parsed JSON body
// — a 200 that returns the wrong shape (e.g. pay quote losing the Safe address,
// or the model catalog going empty) is a regression the status check misses.
const APIS = [
  ['GET', 'https://api.murakumo.cloud/health', b => b.ok === true],
  ['GET', 'https://api.murakumo.cloud/join', b => !!b.tiers],
  ['GET', 'https://api.murakumo.cloud/infer/fleet', b => b['nodes-up'] >= 0],
  ['GET', 'https://api.murakumo.cloud/infer/placement'],
  ['GET', 'https://app.itonami.cloud/itonami/plans', b => !!b.enterprise],
  ['GET', 'https://app.itonami.cloud/itonami/verticals'],
  ['GET', 'https://app.itonami.cloud/itonami/revenue', b => 'usd-in' in b],
  ['GET', 'https://app.itonami.cloud/itonami/funnel', b => 'granted' in b],
  ['GET', 'https://app.itonami.cloud/itonami/models', b => Array.isArray(b) && b.length >= 7],
  ['GET', 'https://app.itonami.cloud/itonami/pay/quote?usd=10',
    b => b.chain === 'base' && b.to && b.to.startsWith('0x') && b.net === 950],
  ['GET', 'https://app.itonami.cloud/itonami/pay/status?did=did:key:z6MkQA', b => 'pending' in b],
  ['GET', 'https://relay.gftd.ai/stats', b => 'settled' in b],
];

async function main() {
  const browser = await chromium.launch({ executablePath: SHELL, headless: true });
  const report = { pages: [], apis: [], ts: Date.now() };

  for (const p of PAGES) {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const jsErrors = [], consoleErrs = [], badReqs = [];
    page.on('pageerror', e => jsErrors.push(String(e).slice(0, 200)));
    page.on('console', m => { if (m.type() === 'error') consoleErrs.push(m.text().slice(0, 200)); });
    page.on('requestfailed', r => badReqs.push(`${r.url().slice(0,80)} ${r.failure()?.errorText||''}`));
    const res = { name: p.name, url: p.url };
    try {
      const resp = await page.goto(p.url, { waitUntil: 'networkidle', timeout: 20000 });
      res.status = resp?.status();
      const html = await page.content();
      res.missing = (p.must || []).filter(m => !html.includes(m));
      // link health: resolve declared links
      res.brokenLinks = [];
      for (const l of (p.links || [])) {
        const abs = l.startsWith('http') ? l : new URL(l, p.url).href;
        try { const r = await page.request.get(abs, { timeout: 10000 }); if (r.status() >= 400) res.brokenLinks.push(`${l} → ${r.status()}`); }
        catch (e) { res.brokenLinks.push(`${l} → ${String(e).slice(0,40)}`); }
      }
      // nav anchors present?
      res.missingAnchors = [];
      for (const a of (p.navAnchors || [])) {
        const has = await page.$(`a[href="${a}"], ${a}`).catch(() => null);
        if (!has) res.missingAnchors.push(a);
      }
      // exercise the join button (does it enroll + attempt relay without crashing?)
      if (p.clickJoin) {
        await page.click('#join').catch(() => {});
        await page.waitForTimeout(4000);
        res.joinStatus = (await page.textContent('#status').catch(() => '') || '').replace(/\s+/g, ' ').trim().slice(0, 240);
      }
    } catch (e) { res.error = String(e).slice(0, 200); }
    res.jsErrors = jsErrors; res.consoleErrs = consoleErrs; res.badReqs = badReqs;
    report.pages.push(res);
    await ctx.close();
  }

  const req = await (await browser.newContext()).request;
  for (const [m, url, assert] of APIS) {
    try { const r = await req.fetch(url, { method: m, timeout: 12000 });
      const entry = { url, status: r.status(), ok: r.ok() };
      if (assert && r.ok()) {
        try { entry.assertOk = !!assert(await r.json()); }
        catch (e) { entry.assertOk = false; entry.assertErr = String(e).slice(0, 60); }
      }
      report.apis.push(entry);
    } catch (e) { report.apis.push({ url, error: String(e).slice(0, 80) }); }
  }
  await browser.close();
  console.log(JSON.stringify(report, null, 2));
}
main().catch(e => { console.error('HARNESS FAIL', e); process.exit(1); });

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

const APIS = [
  ['GET', 'https://api.murakumo.cloud/health'],
  ['GET', 'https://api.murakumo.cloud/join'],
  ['GET', 'https://app.itonami.cloud/itonami/plans'],
  ['GET', 'https://app.itonami.cloud/itonami/verticals'],
  ['GET', 'https://app.itonami.cloud/itonami/revenue'],
  ['GET', 'https://relay.gftd.ai/stats'],
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
  for (const [m, url] of APIS) {
    try { const r = await req.fetch(url, { method: m, timeout: 12000 });
      report.apis.push({ url, status: r.status(), ok: r.ok() });
    } catch (e) { report.apis.push({ url, error: String(e).slice(0, 80) }); }
  }
  await browser.close();
  console.log(JSON.stringify(report, null, 2));
}
main().catch(e => { console.error('HARNESS FAIL', e); process.exit(1); });

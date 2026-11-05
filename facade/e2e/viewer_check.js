// Browser check of the received-payloads viewer (/viewer/) with Playwright (Chromium).
//
// 1. Loads the page from the REAL running facade, enters the token, opens a real receipt and checks that
//    both pulled images render.
// 2. Writes a HOSTILE receipt straight into the facade's data folder (script and markup in every field,
//    and an "image" that is really HTML that the receipt falsely marks viewable), opens it, and checks that
//    nothing runs, nothing hostile is shown as an image, no request leaves the page's own origin, and the
//    browser enforces the page's Content-Security-Policy.
//
// Needs: the facade running (see README.md), a receipt from run_uc04a_e2e.py, and env vars:
//   FACADE (default http://localhost:8091), API_TOKEN, FACADE_DATA_DIR (the facade's data folder), SHOTS (screenshot folder)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const FACADE = process.env.FACADE || 'http://localhost:8091';
const TOKEN = process.env.API_TOKEN;
const DATA = process.env.FACADE_DATA_DIR;
const SHOTS = process.env.SHOTS || '.';
if (!TOKEN || !DATA) { console.error('API_TOKEN and FACADE_DATA_DIR are required'); process.exit(2); }

const results = [];
function check(name, ok, detail) {
  results.push([name, ok]);
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail !== undefined ? ' | ' + detail : ''));
}

// ---- the hostile receipt, written where the facade keeps receipts
const MARKUP = '<img src=x onerror="window.__pwned=1">';
const SCRIPT = '<script>window.__pwned=1</script>';
function writeHostileReceipt() {
  const id = crypto.randomUUID();
  const html = Buffer.from('<html><body><script>window.__pwned=1</script></body></html>');
  const dir = path.join(DATA, 'received', id);
  fs.mkdirSync(path.join(dir, 'images'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'images', 'evil-html.bin'), html);
  const sha = crypto.createHash('sha256').update(html).digest('hex');
  const receipt = {
    id, receivedAt: new Date(Date.now() + 3600 * 1000).toISOString(),       // newest, so it is listed first
    referralId: MARKUP, manifestId: SCRIPT, accepted: true, hapiEntries: 1,
    sender: 'javascript:window.__pwned=1', tokenEndpoint: MARKUP, authorizationDetailsType: SCRIPT,
    stoppedBecause: '"><svg onload=window.__pwned=1>',
    steps: [{ name: '<b>step</b>', ok: true, detail: MARKUP }],
    findings: [{ code: MARKUP, specRef: '<b>ref</b>', message: SCRIPT }],
    objects: [{ entryId: MARKUP, fileKey: 'evil-html', url: 'https://x.example/' + MARKUP, declaredContentType: 'image/jpeg', declaredSize: html.length,
      declaredSha256: sha, status: 'saved', bytes: html.length, sha256: sha, sniffedType: 'image/jpeg', viewable: true }]   // the receipt LIES: these bytes are HTML
  };
  fs.writeFileSync(path.join(dir, 'receipt.json'), JSON.stringify(receipt));
  return id;
}

(async () => {
  const hostileId = writeHostileReceipt();
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1100, height: 1000 } });
  const page = await context.newPage();

  const dialogs = [];
  page.on('dialog', async (d) => { dialogs.push(d.message()); await d.dismiss(); });
  const origin = new URL(FACADE).origin;
  const stray = [];
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (u.protocol === 'blob:' || u.protocol === 'data:') { return; }
    const ok = u.origin === origin && (u.pathname.startsWith('/viewer') || u.pathname.startsWith('/ode/receipts'));
    if (!ok) { stray.push(r.url()); }
  });
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push(String(e)));

  // ---- 1. the page, from the real server
  const resp = await page.goto(FACADE + '/viewer/');
  check('the page loads from the real server at /viewer/', resp.status() === 200 && (await page.title()) === 'Received payloads', resp.status());
  const csp = resp.headers()['content-security-policy'] || '';
  check('it is served with a strict Content-Security-Policy', csp.includes("script-src 'self'") && !csp.includes('unsafe-inline') && csp.includes("frame-ancestors 'none'"), csp);
  const noSlash = await page.goto(FACADE + '/viewer');
  check('/viewer (no trailing slash) forwards to the page', noSlash.status() === 200 && (await page.title()) === 'Received payloads');
  await page.goto(FACADE + '/viewer/');
  check('a person with no token sees no data', (await page.locator('#list').innerText()).trim() === '');

  // ---- wrong token, then the right one
  await page.fill('#token', 'not-the-token');
  await page.click('#load');
  await page.waitForFunction(() => document.getElementById('message').textContent.length > 0);
  check('a wrong token is refused with a plain message and shows no data', (await page.locator('#message').innerText()).includes('not accepted') && (await page.locator('#list tr').count()) === 0);
  await page.fill('#token', TOKEN);
  await page.click('#load');
  await page.waitForSelector('#list tr td');
  await page.screenshot({ path: path.join(SHOTS, 'viewer-1-list.png') });
  check('with the token, the receipts are listed', (await page.locator('#list tr').count()) > 2);

  // ---- 2. a real receipt: both images render
  // Pick the receipt by what the API says it is (both objects viewable), not by row text: the run also leaves a
  // "2 of 2 saved" receipt whose second-scenario HTML 'image' is deliberately not viewable.
  const listing = await (await page.request.get(FACADE + '/ode/receipts?limit=50', { headers: { Authorization: 'Bearer ' + TOKEN } })).json();
  const at = listing.findIndex((r) => r.referralId === 'REF-2026-UC04A-001' && r.objects.length === 2 && r.objects.every((o) => o.viewable));
  check('a real receipt with two viewable images exists to open', at >= 0, 'position ' + at);
  await page.locator('#list tr').nth(at + 1).getByRole('button', { name: 'Open' }).click(); // +1 for the header row
  await page.waitForFunction(() => { const i = [...document.querySelectorAll('#detail img')]; return i.length === 2 && i.every((x) => x.complete && x.naturalWidth > 0); }, null, { timeout: 20000 });
  const detail = await page.locator('#detail').innerText();
  check('the real receipt shows both pulled images, decoded by the browser', (await page.locator('#detail img').count()) === 2);
  check('it names the two known findings', detail.includes('SIGNATURE_PLACEHOLDER') && detail.includes('MANIFEST_EXPIRED_PER_PAYLOAD'));
  check('it shows the referral, manifest and verified digests', detail.includes('REF-2026-UC04A-001') && detail.includes('arex-manifest-intraoral-photos') && detail.includes('saved, digest verified'));
  await page.screenshot({ path: path.join(SHOTS, 'viewer-2-real-receipt.png'), fullPage: true });

  // ---- 3. the hostile receipt
  await page.click('#refresh');
  await page.waitForSelector('#list tr td');
  const bad = page.locator('#list tr').filter({ hasText: 'onerror' }).first();
  await bad.getByRole('button', { name: 'Open' }).click();
  await page.waitForFunction(() => document.getElementById('detail').textContent.includes('onerror'));
  await page.waitForTimeout(1500); // give any injected handler a chance to fire
  const text = await page.locator('#detail').innerText();
  check('hostile markup is shown as plain text, not parsed', text.includes('<img src=x onerror=') && text.includes('<script>window.__pwned=1</script>'));
  check('no script ran (no window flag, no dialog, no page error)', (await page.evaluate(() => window.__pwned)) === undefined && dialogs.length === 0 && consoleErrors.length === 0, JSON.stringify({ dialogs, consoleErrors }));
  check('no <img>, <script>, <svg> or <b> element was created from hostile text', (await page.locator('#detail img').count()) === 0 && (await page.locator('#detail script, #detail svg, #detail b').count()) === 0);
  const caption = await page.locator('#detail figcaption').allInnerTexts();
  check('the lying "image" (HTML bytes marked viewable) was NOT displayed as an image', (await page.locator('#detail figure img').count()) === 0 && caption.some((c) => c.includes('not shown')), JSON.stringify(caption));
  await page.screenshot({ path: path.join(SHOTS, 'viewer-3-hostile-receipt.png'), fullPage: true });

  // the same bytes, fetched directly, must be a download and not a page
  const direct = await page.request.get(`${FACADE}/ode/receipts/${hostileId}/images/evil-html`, { headers: { Authorization: 'Bearer ' + TOKEN } });
  check('the server serves those HTML bytes only as an attachment', direct.headers()['content-type'] === 'application/octet-stream' && (direct.headers()['content-disposition'] || '').startsWith('attachment') && direct.headers()['x-content-type-options'] === 'nosniff', direct.headers()['content-type']);

  // ---- 4. the browser really enforces the CSP, and nothing left the origin
  const inline = await page.evaluate(() => { const s = document.createElement('script'); s.textContent = 'window.__csp = 1'; document.head.appendChild(s); return window.__csp; });
  check('the browser blocks an injected inline script (CSP is enforced)', inline === undefined);
  check('no request went anywhere except the page, the viewer files and /ode/receipts', stray.length === 0, JSON.stringify(stray));

  // ---- 5. forgetting the token
  await page.click('#forget');
  check('"Forget token" empties the page and the tab storage', (await page.locator('#list').innerText()).trim() === '' && (await page.evaluate(() => window.sessionStorage.getItem('ohiaViewerToken'))) === null);

  await browser.close();
  fs.rmSync(path.join(DATA, 'received', hostileId), { recursive: true, force: true });
  const failed = results.filter((r) => !r[1]).length;
  console.log('\n' + results.length + ' checks, ' + failed + ' failed');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error('ERROR', e); process.exit(2); });

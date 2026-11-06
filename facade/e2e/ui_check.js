// Browser check of the Send and Sent tabs and the UC07 view, with Playwright (Chromium) against the REAL facade.
// A stand-in TESTER (a small http server on TESTER_PORT) receives what the page sends. Needs the facade started with
// a registry that lists "tester-ui" with allowedTargetPrefixes http://127.0.0.1:<TESTER_PORT>/fhir, a receipt from
// run_uc07_receive_e2e.py, and env: FACADE, API_TOKEN, FACADE_DATA_DIR, SHOTS, TESTER_PORT (default 9300).
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');

const FACADE = process.env.FACADE || 'http://localhost:8091';
const TOKEN = process.env.API_TOKEN;
const DATA = process.env.FACADE_DATA_DIR;
const SHOTS = process.env.SHOTS || '.';
const PORT = parseInt(process.env.TESTER_PORT || '9300', 10);
if (!TOKEN || !DATA) { console.error('API_TOKEN and FACADE_DATA_DIR are required'); process.exit(2); }
const results = [];
function check(name, ok, detail) { results.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail !== undefined ? ' | ' + detail : '')); }

const decoded = (page, sel, n) => page.waitForFunction(([q, k]) => { const i = [...document.querySelectorAll(q)]; return i.length === k && i.every((x) => x.complete && x.naturalWidth > 0); }, [sel, n], { timeout: 20000 });

const got = [];
const tester = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    got.push({ method: req.method, url: req.url, auth: req.headers.authorization || null, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(200, { 'Content-Type': 'application/fhir+json' });
    res.end('{"resourceType":"Bundle","type":"transaction-response"}');
  });
});

(async () => {
  await new Promise((r) => tester.listen(PORT, '127.0.0.1', r));
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport: { width: 1100, height: 900 } })).newPage();
  const asked = [];
  page.on('dialog', async (d) => { asked.push(d.message()); await d.accept(); });
  const origin = new URL(FACADE).origin;
  const stray = [];
  page.on('request', (r) => { const u = new URL(r.url()); if (u.protocol === 'blob:' || u.protocol === 'data:') return; if (u.origin !== origin) stray.push(r.url()); });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  await page.goto(FACADE + '/viewer/');
  await page.fill('#token', TOKEN);
  await page.click('#load');
  await page.waitForSelector('#list tr td');

  // ---- Send tab
  await page.click('#tab-send');
  check('the Send tab shows, the Received tab hides', await page.locator('#view-send').isVisible() && !(await page.locator('#view-received').isVisible()));
  await page.waitForFunction(() => document.getElementById('seed-status').textContent.length > 0);
  check('it says UC04a data is not loaded yet', (await page.locator('#seed-status').innerText()).includes('not loaded'), await page.locator('#seed-status').innerText());
  await page.click('#seed-button');
  await page.waitForFunction(() => /Loaded \d+ resources/.test(document.getElementById('send-message').textContent), null, { timeout: 90000 });
  const loaded = await page.locator('#send-message').innerText();
  check('Load the data loads the UC04a package and verifies both photos', /Loaded 30 resources and verified 2 image/.test(loaded), loaded);
  await page.waitForFunction(() => document.getElementById('seed-status').textContent.includes('is loaded'));

  const options = await page.locator('#send-client option').allInnerTexts();
  check('the tester list shows the tester and the organization it plays for UC04a', options.length === 1 && options[0].includes('tester-ui') && options[0].includes('org-barton-springs-dental'), JSON.stringify(options));
  check('the address is prefilled from the tester\'s allowed address', (await page.inputValue('#send-target')) === 'http://127.0.0.1:' + PORT + '/fhir', await page.inputValue('#send-target'));

  // ---- image viewer on the Send tab: the images this referral will carry
  await decoded(page, '#send-preview img', 2);
  check('the Send tab previews the two photos this referral will carry, decoded by the browser', (await page.locator('#send-preview figure').count()) === 2);
  await page.locator('#send-preview button.thumb').first().click();
  check('clicking a photo opens the viewer', await page.locator('#lightbox').isVisible());
  await page.waitForFunction(() => { const i = document.getElementById('lightbox-img'); return i.complete && i.naturalWidth > 0; });
  check('the viewer shows it large, with its name, type, size and checksum', (await page.locator('#lightbox-title').innerText()) === 'arex-entry-photo-1' && /image\/jpeg, 632 bytes, sha-256 [0-9a-f]{64}/.test(await page.locator('#lightbox-meta').innerText()), await page.locator('#lightbox-meta').innerText());
  check('it says which image of how many', (await page.locator('#lightbox-count').innerText()) === '1 of 2');
  await page.keyboard.press('ArrowRight');
  check('the right arrow key shows the next image', (await page.locator('#lightbox-title').innerText()) === 'arex-entry-photo-2' && (await page.locator('#lightbox-count').innerText()) === '2 of 2');
  await page.click('#lightbox-next');
  check('Next wraps round to the first', (await page.locator('#lightbox-title').innerText()) === 'arex-entry-photo-1');
  await page.click('#lightbox-prev');
  check('Previous goes back (and wraps)', (await page.locator('#lightbox-title').innerText()) === 'arex-entry-photo-2');
  await page.screenshot({ path: path.join(SHOTS, 'ui-5-lightbox.png') });
  await page.click('#lightbox-zoom');
  const actual = await page.evaluate(() => ({ cls: document.getElementById('lightbox-stage').className, max: getComputedStyle(document.getElementById('lightbox-img')).maxWidth }));
  check('Actual size lifts the fit limit, and the button says how to go back', actual.cls.includes('actual') && actual.max === 'none' && (await page.locator('#lightbox-zoom').innerText()) === 'Fit to screen', JSON.stringify(actual));
  await page.screenshot({ path: path.join(SHOTS, 'ui-6-lightbox-actual-size.png') });
  await page.keyboard.press('Escape');
  check('Escape closes the viewer and puts focus back on the photo you clicked', !(await page.locator('#lightbox').isVisible()) && (await page.evaluate(() => document.activeElement && document.activeElement.className)) === 'thumb');
  await page.locator('#send-preview button.thumb').first().click();
  await page.mouse.click(5, 5); // the dark backdrop
  check('clicking the dark backdrop closes it too', !(await page.locator('#lightbox').isVisible()));

  await page.fill('#send-bearer', 'tester-secret-token-xyz');
  await page.screenshot({ path: path.join(SHOTS, 'ui-1-send.png'), fullPage: true });
  await page.click('#send-button');
  await page.waitForFunction(() => document.getElementById('send-message').textContent === 'Delivered.', null, { timeout: 60000 });
  const result = await page.locator('#send-result').innerText();
  check('the person was asked to confirm before anything was sent', asked.length === 1 && asked[0].includes('UC04a') && asked[0].includes('tester-ui') && asked[0].includes('127.0.0.1:' + PORT), JSON.stringify(asked));
  check('the result shows delivered, their answer 200 and the new referral id', result.includes('delivered') && result.includes('200') && /REF-2026-UC04A-001-[0-9a-f]{8}/.test(result), result.replace(/\n/g, ' ').slice(0, 160));
  await page.screenshot({ path: path.join(SHOTS, 'ui-2-sent-result.png'), fullPage: true });
  check('the tester\'s token field is emptied after the send', (await page.inputValue('#send-bearer')) === '');
  check('the tester received exactly one POST with their token', got.length === 1 && got[0].method === 'POST' && got[0].url === '/fhir' && got[0].auth === 'Bearer tester-secret-token-xyz');
  const sent = JSON.parse(got[0].body);
  check('it is the 30-entry UC04a transaction with OUR photo addresses', sent.type === 'transaction' && sent.entry.length === 30 && got[0].body.includes('/arex/Binary/') && !got[0].body.includes('meridian-teledental.example.org/arex/Binary'), sent.entry.length);

  // the tester's token must not be on our disk
  let leaked = false;
  const walk = (d) => { for (const f of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, f.name); if (f.isDirectory()) walk(p); else if (fs.readFileSync(p).includes('tester-secret-token-xyz')) leaked = true; } };
  walk(DATA);
  check('the tester\'s token is nowhere in the facade\'s data folder', !leaked);

  // ---- refusal shown plainly
  await page.fill('#send-target', 'http://127.0.0.1:' + (PORT + 1) + '/fhir');
  await page.click('#send-button');
  await page.waitForFunction(() => document.getElementById('send-message').className.includes('error'));
  const refusal = await page.locator('#send-message').innerText();
  check('an address outside the tester\'s allowed ones is refused with the server\'s own words, and nothing is sent', refusal.includes('allowed target') && got.length === 1, refusal);

  // ---- Sent tab
  await page.click('#tab-sent');
  await page.waitForSelector('#sent-list tr td');
  const sentRows = await page.locator('#sent-list tr').allInnerTexts();
  check('the Sent tab lists the delivery: UC04a, the tester, delivered', sentRows.length === 2 && sentRows[1].includes('UC04a') && sentRows[1].includes('tester-ui') && sentRows[1].includes('delivered'), JSON.stringify(sentRows));
  await page.locator('#sent-list tr').nth(1).getByRole('button', { name: 'Images' }).click();
  await decoded(page, '#sent-detail img', 2);
  check('the Sent tab shows the two photos that send carried', (await page.locator('#sent-detail figure').count()) === 2 && (await page.locator('#sent-detail').innerText()).includes('UC04a referral REF-2026-UC04A-001-'));
  await page.locator('#sent-detail button.thumb').nth(1).click();
  check('and the viewer opens on the one you clicked', (await page.locator('#lightbox-title').innerText()) === 'arex-entry-photo-2');
  await page.keyboard.press('Escape');
  await page.screenshot({ path: path.join(SHOTS, 'ui-3-sent-tab.png'), fullPage: true });

  // ---- Received tab, UC07
  await page.click('#tab-received');
  await page.selectOption('#filter-usecase', 'uc07');
  await page.waitForFunction(() => document.getElementById('message').textContent.startsWith('Loaded'));
  const rows = await page.locator('#list tr td:nth-child(2)').allInnerTexts();
  check('filtering to UC07 shows only UC07 referrals', rows.length >= 1 && rows.every((t) => t === 'UC07'), JSON.stringify(rows));
  await page.locator('#list tr').nth(1).getByRole('button', { name: 'Open' }).click();
  await page.waitForFunction(() => { const i = [...document.querySelectorAll('#detail img')]; return i.length === 2 && i.every((x) => x.complete && x.naturalWidth > 0); }, null, { timeout: 20000 });
  const detail = await page.locator('#detail').innerText();
  check('the UC07 receipt shows its use case, referral id and both radiographs decoded by the browser', detail.includes('UC07') && detail.includes('REF-2026-UC07-001') && (await page.locator('#detail img').count()) === 2);
  await page.locator('#detail button.thumb').first().click();
  await page.waitForFunction(() => { const i = document.getElementById('lightbox-img'); return i.complete && i.naturalWidth > 0; });
  check('a received PNG opens in the same viewer', /image\/png, \d+ bytes, sha-256 [0-9a-f]{64}/.test(await page.locator('#lightbox-meta').innerText()) && (await page.locator('#lightbox-count').innerText()) === '1 of 2', await page.locator('#lightbox-meta').innerText());
  await page.keyboard.press('Escape');
  await page.screenshot({ path: path.join(SHOTS, 'ui-4-received-uc07.png'), fullPage: true });
  await page.selectOption('#filter-usecase', 'uc04a');
  await page.waitForFunction(() => document.getElementById('message').textContent.startsWith('Loaded'));
  const rows2 = await page.locator('#list tr td:nth-child(2)').allInnerTexts();
  check('filtering to UC04a shows no UC07 referral', rows2.every((t) => t === 'UC04a'), JSON.stringify(rows2));

  // ---- a payload with TWO manifests (left by run_uc04a_two_manifests_e2e.py): manifest column and a PDF download
  await page.selectOption('#filter-usecase', '');
  await page.waitForFunction(() => document.getElementById('message').textContent.startsWith('Loaded'));
  const all = await (await page.request.get(FACADE + '/ode/receipts?limit=50', { headers: { Authorization: 'Bearer ' + TOKEN } })).json();
  const twoAt = all.findIndex((r) => (r.manifestId || '').includes(','));
  check('a receipt with two manifests exists to open', twoAt >= 0, 'position ' + twoAt);
  if (twoAt >= 0) {
    await page.locator('#list tr').nth(twoAt + 1).getByRole('button', { name: 'Open' }).click();
    await page.waitForFunction(() => document.getElementById('detail').textContent.includes('Other files'));
    const heads = await page.locator('#detail th').allInnerTexts();
    check('the table says which manifest each file came from', heads.includes('Manifest'), JSON.stringify(heads));
    check('the two photos are shown and the PDF is listed as a separate file, not as an image', (await page.locator('#detail img').count()) === 2 && (await page.locator('#detail').innerText()).includes('Download arex-entry-doc-1'));
    const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: /Download arex-entry-doc-1/ }).click()]);
    const saved = fs.readFileSync(await download.path());
    check('the PDF downloads intact with a .pdf name', download.suggestedFilename().endsWith('.pdf') && saved.toString('latin1').startsWith('%PDF-1.4') && saved.length > 20, download.suggestedFilename() + ' ' + saved.length + ' bytes');
    await page.screenshot({ path: path.join(SHOTS, 'ui-7-two-manifests.png'), fullPage: true });
  }

  check('nothing left the page\'s own origin, and no script error', stray.length === 0 && errors.length === 0, JSON.stringify({ stray, errors }));
  await page.click('#forget');
  check('"Forget token" also empties the send form and the lists', (await page.locator('#send-client option').count()) === 0 && (await page.locator('#sent-list').innerText()).trim() === '' && (await page.locator('#list').innerText()).trim() === '');

  await browser.close();
  tester.close();
  const failed = results.filter((r) => !r[1]).length;
  console.log('\n' + results.length + ' checks, ' + failed + ' failed');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error('ERROR', e); process.exit(2); });

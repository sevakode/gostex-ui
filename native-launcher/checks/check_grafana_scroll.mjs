/** Real Chromium layout checks for the exact page script shipped in GrafanaAutoScroll.kt.
 * Run with node; set PLAYWRIGHT_MODULE/CHROMIUM_EXECUTABLE if not installed in the runtime.
 * Native WebView/Android lifecycle and production Grafana remain device checks.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
let playwright;
try { playwright = require(process.env.PLAYWRIGHT_MODULE || 'playwright'); }
catch (error) {
  const bundled = join(homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
  if (!existsSync(bundled)) throw error;
  playwright = require(bundled);
}
const source = readFileSync(join(root, 'native-launcher/app/src/main/java/tv/orbit/shield/GrafanaAutoScroll.kt'), 'utf8');
const template = source.match(/private val template = """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert(template, 'Production script must be found');
const script = (speed = 6) => template.replaceAll('__PIXELS_PER_SECOND__', String(speed));
const stop = 'window.__gostexGrafanaScroll?.stop();';
const report = {
  timestampUtc: new Date().toISOString(),
  sourceSha256: createHash('sha256').update(source).digest('hex'),
  passed: false, checks: [],
  notCovered: ['Android WebView lifecycle', 'Live Grafana dashboard layout', 'Saved scroll preference and drawer focus'],
};

const browser = await playwright.chromium.launch({
  headless: true,
  ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}),
});
const fixture = `<!doctype html><meta charset="utf-8"><style>
  html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#111;color:white}
  nav{position:absolute;left:0;top:0;bottom:0;width:180px;overflow:auto}
  main{position:absolute;left:200px;right:20px;top:60px;bottom:20px;overflow:auto}
  main > article{height:1300px;background:linear-gradient(#123,#354)}
  #table{width:280px;height:380px;overflow:auto} #table>div{height:1800px}
  nav>div{height:2600px}
</style><nav><div>Navigation</div></nav>
<main id="dashboard" style="scroll-behavior:smooth !important"><article>
  <h1>Nested dashboard fixture</h1><div id="table"><div>Long inner table</div></div>
</article></main>`;

async function pageFor(html = fixture) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.clock.install({ time: new Date('2026-01-01T00:00:00Z') });
  await page.route('http://grafana-fixture.test/**', route => route.fulfill({ contentType: 'text/html', body: html }));
  await page.goto('http://grafana-fixture.test/d/example');
  await page.clock.pauseAt(new Date('2026-01-01T00:00:01Z'));
  await page.evaluate(() => {
    const set = window.setTimeout.bind(window), clear = window.clearTimeout.bind(window);
    window.__pending = new Set(); window.__fired = 0; window.__scans = 0;
    window.setTimeout = (callback, delay, ...args) => {
      const id = set(() => { window.__pending.delete(id); window.__fired++; callback(...args); }, delay);
      window.__pending.add(id); return id;
    };
    window.clearTimeout = id => { window.__pending.delete(id); clear(id); };
    const hit = document.elementsFromPoint.bind(document);
    document.elementsFromPoint = (...args) => { window.__scans++; return hit(...args); };
  });
  return page;
}
const pos = page => page.evaluate(() => document.querySelector('#dashboard')?.scrollTop ?? document.scrollingElement.scrollTop);
function pass(name) { report.checks.push(name); process.stdout.write(`PASS ${name}\n`); }

try {
  const page = await pageFor();
  await page.evaluate(script());
  await page.clock.runFor(5000);
  assert.equal(await pos(page), 0);
  await page.clock.runFor(6000);
  const slowPosition = await pos(page);
  assert(slowPosition >= 27 && slowPosition <= 32, `Slow position: ${slowPosition}`);
  assert.deepEqual(await page.evaluate(() => [document.querySelector('nav').scrollTop,
    document.querySelector('#table').scrollTop, document.scrollingElement.scrollTop]), [0, 0, 0]);
  assert.equal(await page.evaluate(() => window.__scans), 9);
  pass('Large nested viewport scrolls at six pixels/sec after initial dwell; sidebar, table and window stay still; no repeated DOM discovery');

  await page.evaluate(script());
  await page.clock.runFor(1000);
  assert((await pos(page)) - slowPosition >= 5, 'Repeated install must not restart dwell');
  assert.equal(await page.evaluate(() => window.__pending.size), 1);
  const beforeFast = await pos(page);
  await page.evaluate(script(22));
  await page.clock.runFor(1000);
  assert((await pos(page)) - beforeFast >= 20, 'New speed should apply without restarting or jumping');
  pass('Repeated native callbacks keep a single timer and speed changes apply without resetting position');

  const beforeQuery = await pos(page);
  await page.evaluate(() => {
    history.replaceState({}, '', '?orgId=1&var-region=all&from=now-24h&to=now');
    window.dispatchEvent(new PopStateEvent('popstate'));
    location.hash = 'panel-3';
  });
  await page.clock.runFor(1000);
  assert((await pos(page)) - beforeQuery >= 20);
  assert.equal(await page.evaluate(() => window.__pending.size), 1);
  pass('Grafana bootstrap query/time/variable normalization and same-route history preserve uninterrupted motion');

  await page.evaluate(stop);
  const stopped = await pos(page), fired = await page.evaluate(() => window.__fired);
  await page.clock.runFor(10000);
  assert.equal(await pos(page), stopped);
  assert.equal(await page.evaluate(() => window.__fired), fired);
  assert.equal(await page.evaluate(() => window.__pending.size), 0);
  assert.deepEqual(await page.evaluate(() => {
    const style = document.querySelector('#dashboard').style;
    return [style.getPropertyValue('scroll-behavior'), style.getPropertyPriority('scroll-behavior'), style.getPropertyValue('scroll-snap-type')];
  }), ['smooth', 'important', '']);
  pass('Stop clears every timer, movement and temporary CSS; original style priority is restored');
  await page.close();

  const edge = await pageFor(fixture.replace('height:1300px', 'height:832px').replace('<h1>Nested dashboard fixture</h1><div id="table"><div>Long inner table</div></div>', 'Small overflow'));
  await edge.evaluate(script());
  await edge.clock.runFor(8100);
  const bottom = await pos(edge);
  assert.equal(bottom, 12);
  await edge.clock.runFor(4000);
  assert.equal(await pos(edge), bottom, 'Must dwell at end, not jump to beginning');
  await edge.clock.runFor(2900);
  assert((await pos(edge)) < bottom && (await pos(edge)) > 0, 'Must reverse gently');
  pass('Bottom pause is followed by gentle upward movement without wraparound or flashing');
  await edge.close();

  const delayed = await pageFor(fixture.replace('height:1300px', 'height:100px').replace('<h1>Nested dashboard fixture</h1><div id="table"><div>Long inner table</div></div>', 'Empty'));
  await delayed.evaluate(script());
  await delayed.clock.runFor(8000);
  assert.equal(await pos(delayed), 0);
  assert((await delayed.evaluate(() => window.__fired)) <= 5);
  await delayed.evaluate(() => document.querySelector('article').style.height = '1700px');
  await delayed.clock.runFor(12000);
  assert((await pos(delayed)) > 10);
  pass('Short dashboards stay stationary with sparse timers; late arriving panels become scrollable');
  await delayed.close();

  const login = await pageFor(fixture.replace('<h1>', '<input type="password"><h1>'));
  await login.evaluate(script());
  await login.clock.runFor(16000);
  assert.equal(await pos(login), 0);
  assert.equal(await login.evaluate(() => window.__scans), 0);
  await login.evaluate(() => document.querySelector('input').remove());
  await login.clock.runFor(14000);
  assert((await pos(login)) > 20);
  await login.evaluate(() => {
    const dialog = document.createElement('div'); dialog.role = 'dialog'; dialog.textContent = 'Session expired'; document.body.append(dialog);
  });
  await login.clock.runFor(2200);
  const loginStopped = await pos(login);
  await login.clock.runFor(8000);
  assert.equal(await pos(login), loginStopped);
  pass('Visible login forms and later modal dialogs suspend scrolling; resolved login allows a loaded dashboard');
  await login.close();

  for (const transition of ['pagehide', 'visibilitychange', 'route']) {
    const leaving = await pageFor();
    await leaving.evaluate(script());
    await leaving.clock.runFor(9000);
    await leaving.evaluate(transition => {
      if (transition === 'route') history.pushState({}, '', '/login');
      else if (transition === 'visibilitychange') {
        Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
        document.dispatchEvent(new Event('visibilitychange'));
      } else window.dispatchEvent(new Event('pagehide'));
    }, transition);
    await leaving.clock.runFor(100);
    const position = await pos(leaving), callbacks = await leaving.evaluate(() => window.__fired);
    await leaving.clock.runFor(8000);
    assert.equal(await pos(leaving), position);
    assert.equal(await leaving.evaluate(() => window.__fired), callbacks);
    assert.equal(await leaving.evaluate(() => window.__pending.size), 0);
    assert.equal(await leaving.evaluate(() => '__gostexGrafanaScroll' in window), false);
    await leaving.close();
  }
  pass('Page leave, hidden document and same-document URL navigation fully cancel callbacks and remove state');

  const replacement = await pageFor();
  await replacement.evaluate(script());
  await replacement.clock.runFor(10000);
  await replacement.evaluate(() => {
    const old = document.querySelector('main');
    const fresh = old.cloneNode(true); fresh.style.removeProperty('scroll-behavior'); fresh.style.removeProperty('scroll-snap-type');
    old.replaceWith(fresh);
  });
  await replacement.clock.runFor(12000);
  assert((await pos(replacement)) > 15);
  assert.equal(await replacement.evaluate(() => window.__pending.size), 1);
  pass('A replaced dashboard scroll container is rediscovered without accumulating timers');
  await replacement.close();

  const rootPage = await pageFor('<!doctype html><style>body{margin:0;height:2200px}</style><h1>Document scroller</h1>');
  await rootPage.evaluate(script());
  await rootPage.clock.runFor(11000);
  assert((await pos(rootPage)) >= 27);
  await rootPage.evaluate(stop);
  pass('Standard document scrolling works as a fallback without Grafana-specific selectors');
  await rootPage.close();
  report.passed = true;
} catch (error) {
  report.failure = String(error.stack || error);
  throw error;
} finally {
  await browser.close();
  const output = join(root, '.reports/grafana-scroll-checks.json');
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
  process.stdout.write(`${JSON.stringify({ passed: report.passed, checks: report.checks.length, report: output })}\n`);
}

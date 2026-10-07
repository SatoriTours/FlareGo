import fs from "node:fs/promises";
import assert from "node:assert/strict";
const base = process.env.FLAREGO_BASE_URL || "http://127.0.0.1:4173";
const endpoint = process.env.FLAREGO_CDP || "http://127.0.0.1:9227";
const targets = await (await fetch(endpoint + "/json/list")).json();
const target = targets.find((t) => t.type === "page");
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});
let seq = 0;
const pending = new Map();
const errors = [];
ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.id) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? p.reject(msg.error) : p.resolve(msg.result);
  } else if (msg.method === "Runtime.exceptionThrown")
    errors.push(
      msg.params.exceptionDetails.text +
        ": " +
        (msg.params.exceptionDetails.exception?.description || ""),
    );
};
const cdp = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
async function evaluate(expression) {
  const r = await cdp("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.exceptionDetails)
    throw Error(
      r.exceptionDetails.exception?.description || r.exceptionDetails.text,
    );
  return r.result.value;
}
async function viewport(width, height) {
  await cdp("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width < 700,
  });
}
async function navigate(path) {
  await cdp("Page.navigate", { url: base + "/" + path });
  await pause(450);
  assert.equal(await evaluate("document.readyState"), "complete");
}
async function shot(name, full = false) {
  await evaluate(
    "document.querySelector('#toast')?.classList.remove('visible')",
  );
  await pause(220);
  if (full) {
    const { cssContentSize } = await cdp("Page.getLayoutMetrics");
    const r = await cdp("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: true,
      clip: {
        x: 0,
        y: 0,
        width: cssContentSize.width,
        height: cssContentSize.height,
        scale: 1,
      },
    });
    await fs.writeFile("design/" + name, Buffer.from(r.data, "base64"));
  } else {
    const r = await cdp("Page.captureScreenshot", { format: "png" });
    await fs.writeFile("design/" + name, Buffer.from(r.data, "base64"));
  }
}
const checks = [];
async function check(label, expression) {
  assert.ok(await evaluate(expression), label);
  checks.push(label);
  console.log("PASS", label);
}
const click = (selector) =>
  evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
await cdp("Page.enable");
await cdp("Runtime.enable");
await cdp("Network.enable");
await cdp("Network.setCacheDisabled", { cacheDisabled: true });
await viewport(1440, 1000);
await navigate("index.html");
await check(
  "Desktop overview renders",
  `document.querySelector('h1').textContent.includes('一手掌握')`,
);
await shot("desktop-overview.png");
await viewport(390, 844);
await navigate("index.html");
await check(
  "Mobile bottom navigation visible",
  `getComputedStyle(document.querySelector('.bottom-nav')).display==='flex'`,
);
await shot("mobile-overview.png");
// A broken page-search scope or account switch that loses the current page
// must fail these interaction checks; screenshots alone cannot catch either.
await navigate("index.html?screen=resources");
await check(
  "Search stays collapsed until the header icon is tapped",
  `document.querySelector('#resource-search')===null && document.querySelector('#topbar').textContent.includes('云资源')`,
);
await click('#topbar [data-action="page-search"]');
await evaluate(
  `document.querySelector('#resource-search').value='media';document.querySelector('#resource-search').dispatchEvent(new Event('input',{bubbles:true}))`,
);
await check(
  "Header search filters the current resource list",
  `document.querySelectorAll('.resource-tile').length===1 && document.querySelector('.resource-tile').textContent.includes('media-assets')`,
);
await shot("mobile-resource-search.png");
await click('[data-action="close-page-search"]');
await check(
  "Cancel search restores the resource list",
  `document.querySelector('#resource-search')===null && document.querySelectorAll('.resource-tile').length===6`,
);
await click('#topbar [data-action="cloud-menu"]');
await check(
  "Logo opens a cloud and account drawer",
  `document.querySelector('.cloud-drawer')?.textContent.includes('Cloudflare') && document.querySelector('.cloud-drawer').textContent.includes('Amazon Web Services')`,
);
await shot("mobile-cloud-drawer.png");
await click('.cloud-drawer [data-account="sandbox"]');
await check(
  "Account switch preserves the resource page and isolates resources",
  `document.querySelector('#topbar').textContent.includes('云资源') && document.querySelectorAll('.resource-tile').length===0`,
);
await click('#topbar [data-action="cloud-menu"]');
await click('.cloud-drawer [data-account="production"]');
await click('#topbar [data-action="cloud-menu"]');
await evaluate(`new Promise(resolve => {
  function ready() {
    const drawer = document.querySelector('.cloud-drawer');
    if (drawer && drawer.getBoundingClientRect().left >= -0.5) resolve();
    else requestAnimationFrame(ready);
  }
  ready();
})`);
await cdp("Input.dispatchTouchEvent", {
  type: "touchStart",
  touchPoints: [{ x: 230, y: 380 }],
});
await cdp("Input.dispatchTouchEvent", {
  type: "touchMove",
  touchPoints: [{ x: 90, y: 382 }],
});
await cdp("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
await check(
  "A left swipe dismisses the drawer",
  `document.querySelector('.cloud-drawer')===null`,
);
await navigate("index.html?screen=domains");
await click('#topbar [data-action="page-search"]');
await evaluate(
  `document.querySelector('#domain-search').value='studio';document.querySelector('#domain-search').dispatchEvent(new Event('input',{bubbles:true}))`,
);
await check(
  "Domain header search stays within the selected account",
  `document.querySelectorAll('.table tbody tr').length===1 && document.querySelector('.table tbody').textContent.includes('studio.design')`,
);
await navigate("index.html?screen=dns");
await click('#topbar [data-action="page-search"]');
await evaluate(
  `document.querySelector('#dns-search').value='www';document.querySelector('#dns-search').dispatchEvent(new Event('input',{bubbles:true}))`,
);
await check(
  "DNS header search filters only this zone",
  `document.querySelectorAll('.dns-record').length===1 && document.querySelector('.dns-record').textContent.includes('www.flarego.dev')`,
);
await navigate("index.html?screen=buy");
await click('#topbar [data-action="page-search"]');
await evaluate(
  `document.querySelector('#purchase-search input').value='my-studio';document.querySelector('#purchase-search').requestSubmit()`,
);
await check(
  "Registration search updates suggestions and collapses the input",
  `document.querySelector('#purchase-search')===null && document.querySelector('#purchase-results').textContent.includes('my-studio.com')`,
);
await navigate("index.html");

for (const screen of [
  "domains",
  "dns",
  "buy",
  "resources",
  "worker",
  "bucket",
  "billing",
  "accounts",
  "jobs",
]) {
  await navigate("index.html?screen=" + screen);
  await check(
    screen + " renders without horizontal overflow",
    `document.documentElement.scrollWidth <= 390 && document.querySelector('main').children.length > 0`,
  );
  await shot("mobile-" + screen + ".png");
}
await navigate("index.html?screen=dns");
await click('[data-record="1"]');
await evaluate(
  `document.querySelector('#dns-content').value='192.0.2.42';document.querySelector('#dns-form').requestSubmit()`,
);
await check(
  "DNS change preview contains old and new values",
  `document.querySelector('.modal').textContent.includes('192.0.2.10') && document.querySelector('.modal').textContent.includes('192.0.2.42')`,
);
await shot("mobile-dns-confirm.png");
await click("#dns-confirm");
await check(
  "DNS change saved and audited",
  `document.querySelector('main').textContent.includes('192.0.2.42') && jobs[0].name.includes('DNS')`,
);
await click('[data-action="dns-new"]');
await evaluate(
  `document.querySelector('#dns-type').value='MX';document.querySelector('#dns-type').dispatchEvent(new Event('change'));`,
);
await check(
  "MX has priority and cannot use proxy",
  `document.querySelector('#proxy-switch').disabled && getComputedStyle(document.querySelector('#priority-field')).display==='grid'`,
);
await click('[data-action="close-modal"]');
await evaluate(`go('buy')`);
await click('[data-purchase="hello-flarego.com"]');
await check(
  "Purchase requires explicit acknowledgement; auto-renew off",
  `document.querySelector('[type=checkbox]').required && document.querySelector('#renew-switch').getAttribute('aria-checked')==='false'`,
);
await shot("mobile-purchase-confirm.png");
await evaluate(
  `document.querySelector('[type=checkbox]').checked=true;document.querySelector('#purchase-form').requestSubmit()`,
);
await pause(1450);
await check(
  "Async registration completes",
  `document.querySelector('.modal').textContent.includes('属于你') && jobs[0].state==='succeeded'`,
);
await click("#setup-dns");
await check(
  "Purchased domain routes to empty DNS list",
  `document.querySelector('h1').textContent==='hello-flarego.com' && document.querySelector('main').textContent.includes('暂无解析')`,
);
await evaluate(`go('buy')`);
await click('[data-purchase="hello-flarego.dev"]');
await click('[data-scenario="unknown"]');
await evaluate(
  `document.querySelector('[type=checkbox]').checked=true;document.querySelector('#purchase-form').requestSubmit()`,
);
await click('[data-goto="jobs"].btn');
await click('[data-job="0"]');
await check(
  "Unknown purchase outcome offers reconciliation",
  `document.querySelector('#reconcile')!==null && jobs[0].state==='unknown'`,
);
await click("#reconcile");
await check(
  "Reconciliation preserves one operation",
  `jobs[0].state==='succeeded' && jobs.filter(j=>j.name==='注册 hello-flarego.dev').length===1 && domainList().some(d=>d.name==='hello-flarego.dev')`,
);
await evaluate(`go('resources')`);
await click('#topbar [data-action="page-search"]');
await evaluate(
  `document.querySelector('#resource-search').value='media';document.querySelector('#resource-search').dispatchEvent(new Event('input',{bubbles:true}))`,
);
await check(
  "Resource search filters list",
  `document.querySelectorAll('.resource-tile').length===1`,
);
await click('[data-action="create-resource"]');
await evaluate(
  `document.querySelector('#resource-name').value='new-assets';document.querySelector('#resource-form').requestSubmit()`,
);
await check(
  "Resource creation visible in inventory",
  `document.querySelector('main').textContent.includes('new-assets')`,
);
await evaluate(`selectedResource='api-gateway';go('worker')`);
await click('[data-action="versions"]');
await click('[data-version="v1.8.1"]');
await click("#confirm-version");
await check(
  "Worker version changes after confirmation",
  `currentVersion==='v1.8.1' && jobs[0].name.includes('切换')`,
);
await evaluate(`go('billing')`);
await click('[data-action="budget"]');
await evaluate(
  `document.querySelector('#budget-amount').value='200';document.querySelector('#budget-form').requestSubmit()`,
);
await check(
  "Budget updates amount and percentage",
  `document.querySelector('main').textContent.includes('$200.00') && document.querySelector('main').textContent.includes('24.3%')`,
);
await evaluate(`go('accounts')`);
await click('[data-action="connect"]');
await evaluate(
  `document.querySelector('#connection-token').value='demo-token';document.querySelector('#connect-form').requestSubmit()`,
);
await check(
  "Demo credential verified",
  `document.querySelector('.modal').textContent.includes('示例 Cloudflare 账号')`,
);
await click('[data-action="switch-account"].btn');
await click('[data-account="sandbox"]');
await check(
  "Account switch isolates inventory",
  `resourceList().length===0 && domainList().length===0 && document.querySelector('#topbar').textContent.includes('开发沙箱')`,
);
await evaluate(`go('billing')`);
await check(
  "Sandbox billing does not show production costs",
  `!document.querySelector('main').textContent.includes('48.62') && document.querySelector('main').textContent.includes('费用未知')`,
);
await evaluate(`go('jobs')`);
await check(
  "Sandbox operations are isolated",
  `!document.querySelector('main').textContent.includes('注册 hello-flarego.dev')`,
);
await evaluate(`actions.search()`);
await check(
  "Global search available",
  `document.querySelector('#global-search')!==null`,
);
await cdp("Input.dispatchKeyEvent", {
  type: "keyDown",
  key: "Escape",
  code: "Escape",
});
await check("Escape closes modal", `document.querySelector('.modal')===null`);
await viewport(1640, 900);
await navigate("design-board.html");
await pause(500);
await check(
  "Design board has 8 live mobile screens",
  `document.querySelectorAll('iframe').length===8 && [...document.querySelectorAll('iframe')].every(f=>f.contentDocument.querySelector('main').children.length>0)`,
);
await shot("app-design-board.png", true);
await viewport(1440, 1100);
await navigate("architecture.html");
await check(
  "Architecture provider mapping responds",
  `document.querySelector('[data-id=aws]').click();document.querySelector('#port-demo').textContent.includes('EC2')`,
);
await evaluate(`document.querySelector('[data-id=cloudflare]').click()`);
await shot("architecture-blueprint.png", true);
assert.deepEqual(errors, [], "No uncaught browser errors");
checks.push("No uncaught browser errors");
await fs.writeFile(
  "design/verification.json",
  JSON.stringify(
    {
      date: new Date().toISOString(),
      browser: "Chromium / CDP",
      viewports: ["390 × 844", "1440 × 1000", "1640 × 900"],
      checks,
      errors,
      note: "Mock UI only; no cloud API operations performed",
    },
    null,
    2,
  ),
);
console.log(`Completed ${checks.length} browser checks.`);
ws.close();

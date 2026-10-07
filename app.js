"use strict";
const $ = (s) => document.querySelector(s);
const esc = (s) =>
  String(s).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const paths = {
  flame: "M13 2 4 13h6l-1 9 11-13h-7l1-7Z",
  grid: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  globe:
    "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z M3 12h18 M12 3c-5 5-5 13 0 18 5-5 5-13 0-18Z",
  box: "m12 3 9 5v9l-9 5-9-5V8l9-5Z M3 8l9 5 9-5 M12 13v9 M7.5 5.5l9 5",
  card: "M3 5h18v14H3z M3 10h18 M6 15h4",
  cloud: "M7 18H5a4 4 0 0 1-1-7.9 6 6 0 0 1 11.7-1.6A4.5 4.5 0 1 1 18 18H7Z",
  search: "M20 20l-5-5 M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z",
  bell: "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4",
  down: "m7 10 5 5 5-5",
  right: "m9 5 7 7-7 7",
  arrow: "M5 12h14 m-5-5 5 5-5 5",
  back: "M19 12H5 m5-5-5 5 5 5",
  plus: "M12 5v14 M5 12h14",
  check: "m5 12 4 4L19 6",
  close: "m6 6 12 12 M6 18 18 6",
  server:
    "M3 3h18v7H3z M3 14h18v7H3z M7 6.5h.01 M7 17.5h.01 M11 6.5h6 M11 17.5h6",
  bolt: "m13 2-9 12h7l-1 8 10-12h-7l0-8Z",
  database:
    "M20 6c0 2-3.6 3-8 3S4 8 4 6s3.6-3 8-3 8 1 8 3Z M4 6v12c0 2 3.6 3 8 3s8-1 8-3V6 M4 12c0 2 3.6 3 8 3s8-1 8-3",
  clock: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z M12 7v5l3 2",
  settings:
    "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1 1-3Z",
  shield: "m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z m-4 9 3 3 5-5",
  link: "m9 15 6-6 M8 17l-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0 M16 7l1-1a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0",
  refresh:
    "M20 8a8 8 0 0 0-14-3L3 8 M3 3v5h5 M4 16a8 8 0 0 0 14 3l3-3 M21 21v-5h-5",
  more: "M5 12h.01 M12 12h.01 M19 12h.01",
  external: "M14 3h7v7 M21 3l-9 9 M10 3H3v18h18v-7",
  download: "M12 3v12 m-4-4 4 4 4-4 M4 17v4h16v-4",
  info: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z M12 11v6 M12 7h.01",
  code: "m8 6-6 6 6 6 M16 6l6 6-6 6 M14 3l-4 18",
  folder: "M3 6h7l2 3h9v12H3V6Z",
  lock: "M5 10h14v11H5z M8 10V7a4 4 0 0 1 8 0v3",
  sliders: "M4 7h16 M4 17h16 M8 4v6 M16 14v6",
};
function icon(name, cls = "") {
  return `<svg class="icon ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.55" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${paths[name] || paths.box}"/></svg>`;
}
const logo = `<span class="brand-mark">${icon("flame")}</span>`;
const navs = [
  ["overview", "grid", "总览"],
  ["domains", "globe", "域名"],
  ["resources", "box", "资源"],
  ["billing", "card", "账单"],
  ["accounts", "cloud", "账号"],
];
const titles = {
  overview: "工作区总览",
  domains: "域名管理",
  dns: "DNS 解析",
  buy: "购买域名",
  resources: "资源管理",
  worker: "Worker 详情",
  bucket: "存储桶详情",
  billing: "账单与费用",
  accounts: "云账号",
  jobs: "操作记录",
};
const qs = new URLSearchParams(location.search);
let screen = qs.get("screen") || location.hash.slice(1) || "overview";
if (!titles[screen]) screen = "overview";
let account = "production",
  domainTab = "all",
  resourceTab = "all",
  chartPeriod = "30d",
  query = "",
  selectedDomain = "flarego.dev",
  demoScenario = "success",
  budget = 100,
  currentVersion = "v1.8.2";
let domains = [
  {
    name: "flarego.dev",
    status: "正常",
    plan: "Pro",
    expires: "2027.06.12",
    registrar: true,
    account: "production",
  },
  {
    name: "getflare.app",
    status: "正常",
    plan: "Free",
    expires: "2027.03.08",
    registrar: true,
    account: "production",
  },
  {
    name: "studio.design",
    status: "即将到期",
    plan: "Free",
    expires: "2026.10.21",
    registrar: true,
    account: "production",
  },
  {
    name: "flarego.io",
    status: "正常",
    plan: "Free",
    expires: "第三方注册",
    registrar: false,
    account: "production",
  },
];
let resources = [
  {
    name: "api-gateway",
    type: "Workers",
    kind: "edge",
    icon: "bolt",
    usage: "182 万",
    unit: "本月请求",
    spec: "全球边缘网络",
    account: "production",
  },
  {
    name: "image-transform",
    type: "Workers",
    kind: "edge",
    icon: "bolt",
    usage: "68 万",
    unit: "本月请求",
    spec: "全球边缘网络",
    account: "production",
  },
  {
    name: "media-assets",
    type: "R2",
    kind: "storage",
    icon: "box",
    usage: "128.4 GB",
    unit: "已用存储",
    spec: "对象存储 · Standard",
    account: "production",
  },
  {
    name: "backup-archive",
    type: "R2",
    kind: "storage",
    icon: "box",
    usage: "32.1 GB",
    unit: "已用存储",
    spec: "对象存储 · Standard",
    account: "production",
  },
  {
    name: "app-database",
    type: "D1",
    kind: "database",
    icon: "database",
    usage: "246 MB",
    unit: "数据库大小",
    spec: "SQL 数据库",
    account: "production",
  },
  {
    name: "session-cache",
    type: "KV",
    kind: "storage",
    icon: "server",
    usage: "2,840",
    unit: "键值对",
    spec: "全球键值存储",
    account: "production",
  },
];
let records = [
  {
    id: 1,
    domain: "flarego.dev",
    type: "A",
    name: "@",
    content: "192.0.2.10",
    proxy: true,
    ttl: "自动",
  },
  {
    id: 2,
    domain: "flarego.dev",
    type: "CNAME",
    name: "www",
    content: "flarego.dev",
    proxy: true,
    ttl: "自动",
  },
  {
    id: 3,
    domain: "flarego.dev",
    type: "CNAME",
    name: "api",
    content: "api-gateway.workers.dev",
    proxy: true,
    ttl: "自动",
  },
  {
    id: 4,
    domain: "flarego.dev",
    type: "MX",
    name: "@",
    content: "mail.example.com",
    proxy: false,
    ttl: "3600",
    priority: 10,
  },
];
let jobs = [
  {
    name: "更新 www 的 DNS 解析",
    description: "flarego.dev · CNAME",
    state: "succeeded",
    time: "14 分钟前",
    icon: "globe",
    account: "production",
  },
  {
    name: "部署 api-gateway",
    description: "Workers · v1.8.2",
    state: "succeeded",
    time: "38 分钟前",
    icon: "bolt",
    account: "production",
  },
  {
    name: "创建 media-assets",
    description: "R2 · Standard",
    state: "succeeded",
    time: "2 小时前",
    icon: "box",
    account: "production",
  },
];
let purchaseResults = null;
let searchOpen = false;
let purchaseKeyword = "flarego";
let activeTrap = null;
const domainList = () => domains.filter((d) => d.account === account);
const resourceList = () => resources.filter((r) => r.account === account);
const acctLabel = () =>
  account === "production" ? "个人生产账号" : "开发沙箱";
function btn(label, action, ico = "plus", secondary = false) {
  return `<button class="btn ${secondary ? "secondary" : ""}" ${action}>${ico ? icon(ico) : ""}${label}</button>`;
}
function heading(eye, title, sub, action = "") {
  return `<div class="page-heading"><div><div class="eyebrow">${eye}</div><h1>${title}</h1><p class="subtitle">${sub}</p></div>${action}</div>`;
}
function compactHeading(title, action = "", back = "") {
  return `<div class="compact-heading"><div class="compact-heading-title">${back ? `<button class="icon-button detail-back" data-goto="${back}" aria-label="返回域名">${icon("back")}</button>` : ""}<h1>${esc(title)}</h1></div>${action}</div>`;
}
function detailHeading(
  title,
  subtitle,
  status,
  back,
  resourceIcon,
  color = "",
) {
  return `<div class="detail-heading"><button class="icon-button detail-back" data-goto="${back}" aria-label="${back === "domains" ? "返回域名" : "返回资源"}">${icon("back")}</button><span class="resource-icon desktop-only ${color}">${icon(resourceIcon)}</span><div class="detail-title"><h1 title="${esc(title)}">${esc(title)}</h1><p class="subtitle">${esc(subtitle)}</p></div><span class="badge">${esc(status)}</span></div>`;
}
function cfLogo() {
  return `<span class="cf-logo">${icon("cloud")}</span>`;
}
function renderShell() {
  const active = ["dns", "buy"].includes(screen)
    ? "domains"
    : ["worker", "bucket"].includes(screen)
      ? "resources"
      : screen;
  $("#sidebar").innerHTML =
    `<a class="brand" href="#overview" data-goto="overview">${logo}FlareGo<span style="color:#98a28a;font-size:23px">.</span></a><button class="workspace" data-action="switch-account"><span class="workspace-avatar">J</span><span style="text-align:left">Jim 的工作区<br><small>个人空间</small></span>${icon("down")}</button><div class="label">WORKSPACE</div><div class="side-nav">${navs.map(([id, ic, label]) => `<button class="nav-item ${active === id ? "active" : ""}" data-goto="${id}">${icon(ic)}${label}${id === "accounts" ? '<span class="nav-count">1</span>' : ""}</button>`).join("")}</div><div class="label" style="margin-top:23px">ACTIVITY</div><div class="side-nav"><button class="nav-item ${screen === "jobs" ? "active" : ""}" data-goto="jobs">${icon("clock")}操作记录</button></div><div class="sidebar-bottom"><button class="connection-mini" data-goto="accounts">${cfLogo()}<span>Cloudflare 已连接<br><small>${esc(acctLabel())}</small></span><i class="live-dot" style="margin-left:auto"></i></button><a class="nav-item" href="architecture.html">${icon("code")}架构蓝图 ${icon("external")}</a><a class="nav-item" href="design-board.html">${icon("grid")}界面设计 ${icon("external")}</a></div>`;
  renderTopbar();
  $("#bottom-nav").innerHTML = navs
    .map(
      ([id, ic, label]) =>
        `<button class="${active === id ? "active" : ""}" data-goto="${id}" ${active === id ? 'aria-current="page"' : ""}><span class="nav-icon">${icon(ic)}</span>${label}</button>`,
    )
    .join("");
}
function renderTopbar() {
  const detail = ["dns", "worker", "bucket"].includes(screen);
  const pageTitle =
    screen === "dns"
      ? selectedDomain
      : ["worker", "bucket"].includes(screen)
        ? selectedResource
        : { overview: "总览", domains: "域名", resources: "云资源" }[screen] ||
          titles[screen];
  const pageSubtitle =
    screen === "dns"
      ? "DNS 解析 · 正常"
      : screen === "worker"
        ? `Workers · ${currentVersion} · 运行中`
        : screen === "bucket"
          ? "R2 · 对象存储"
          : `Cloudflare · ${acctLabel()}`;
  const back = ["dns", "buy"].includes(screen)
    ? "domains"
    : ["worker", "bucket"].includes(screen)
      ? "resources"
      : "";
  const primary = {
    domains: ['data-goto="buy"', "plus", "购买域名"],
    resources: ['data-action="create-resource"', "plus", "创建资源"],
    dns: ['data-action="dns-new"', "plus", "添加解析记录"],
    worker: ['data-action="versions"', "more", "管理部署版本"],
    billing: ['data-action="export-billing"', "download", "导出费用明细"],
    accounts: ['data-action="connect"', "plus", "添加云账号"],
  }[screen];
  const primaryButton = primary
    ? `<button class="icon-button header-primary" ${primary[0]} aria-label="${primary[2]}" title="${primary[2]}">${icon(primary[1])}</button>`
    : `<button class="icon-button" data-goto="jobs" aria-label="查看操作记录">${icon("bell")}</button>`;
  $("#topbar").innerHTML =
    `<div class="topbar-row"><div class="header-identity">${back ? `<button class="icon-button header-back" data-goto="${back}" aria-label="${back === "domains" ? "返回域名" : "返回资源"}">${icon("back")}</button>` : ""}<button class="provider-switch" data-action="cloud-menu" aria-label="打开云厂商与账号菜单" title="Cloudflare · ${esc(acctLabel())}">${logo}</button><div class="header-title"><span class="header-page-title" role="heading" aria-level="1" title="${esc(pageTitle)}">${esc(pageTitle)}</span><small>${esc(pageSubtitle)}</small></div></div><div class="header-tools"><button class="icon-button header-search-trigger ${searchOpen ? "active" : ""}" data-action="page-search" aria-label="搜索${detail ? "当前资源" : pageTitle}" aria-expanded="${searchOpen}" aria-controls="page-search-panel">${icon("search")}</button>${primaryButton}</div></div><div id="page-search-panel" ${searchOpen ? "" : "hidden"}>${searchOpen ? pageSearchHTML() : ""}</div>`;
}
function pageSearchHTML() {
  const cancel = `<button type="button" class="text-btn search-cancel" data-action="close-page-search">取消</button>`;
  if (screen === "buy")
    return `<form class="header-query" id="purchase-search"><div class="search-field">${icon("search")}<input name="keyword" value="${esc(purchaseKeyword)}" required pattern="[A-Za-z0-9-]+" maxlength="50" placeholder="输入品牌名或域名关键词" aria-label="域名关键词"></div><button class="btn" type="submit">搜索</button>${cancel}</form>`;
  const inputs = {
    domains: ["domain-search", "搜索域名…"],
    resources: ["resource-search", "搜索资源名称…"],
    dns: ["dns-search", "搜索主机、类型或记录内容…"],
  };
  const [id, placeholder] = inputs[screen];
  return `<div class="header-query"><div class="search-field">${icon("search")}<input id="${id}" value="${esc(query)}" placeholder="${placeholder}" aria-label="${placeholder}"><button class="icon-button clear-query" data-action="clear-page-search" aria-label="清空搜索">${icon("close")}</button></div>${cancel}</div>`;
}
function openPageSearch() {
  if (!["domains", "resources", "dns", "buy"].includes(screen))
    return actions.search();
  if (searchOpen) return closePageSearch();
  searchOpen = true;
  renderTopbar();
  $("#page-search-panel input")?.focus();
}
function closePageSearch() {
  searchOpen = false;
  query = "";
  render();
  $('#topbar [data-action="page-search"]')?.focus();
}
function refreshSearchResults() {
  if (screen === "domains") $("#domain-table").innerHTML = domainTable();
  if (screen === "resources") $("#resource-tiles").innerHTML = resourceTiles();
  if (screen === "dns") {
    const input = $("#dns-search"),
      start = input.selectionStart;
    $("#main").innerHTML = dnsScreen();
    input.focus();
    input.setSelectionRange(start, start);
  }
}
function openCloudMenu() {
  const providers = [
    ["aws", "aws", "Amazon Web Services"],
    ["google", "G", "Google Cloud"],
    ["azure", "⊞", "Microsoft Azure"],
    ["ali", "阿", "阿里云"],
    ["tencent", "腾", "腾讯云"],
  ];
  modal(
    "云与账号",
    `<div class="drawer-current">${cfLogo()}<div><h3>Cloudflare</h3><small><i class="live-dot"></i>已连接 · 当前云厂商</small></div><span class="badge">当前</span></div><div class="drawer-accounts">${[
      ["production", "个人生产账号"],
      ["sandbox", "开发沙箱"],
    ]
      .map(
        ([id, name]) =>
          `<button class="drawer-account ${account === id ? "selected" : ""}" data-account="${id}" ${account === id ? 'aria-current="true"' : ""}><span class="workspace-avatar">${id === "production" ? "P" : "D"}</span><span>${name}<small>${id === "production" ? "生产资源" : "独立开发环境"}</small></span>${account === id ? icon("check") : icon("right")}</button>`,
      )
      .join(
        "",
      )}</div><div class="drawer-section-title">其他云厂商</div><div class="drawer-providers">${providers.map(([id, mark, name]) => `<button class="drawer-provider" disabled><span class="provider-logo ${id}">${mark}</span><span>${name}<small>尚未连接</small></span><span class="badge neutral">规划中</span></button>`).join("")}</div><div class="drawer-footer"><button class="btn secondary full" data-goto="accounts">${icon("settings")}管理云连接</button><small>日常操作始终使用当前云与账号</small></div>`,
    () => {
      const drawer = $(".cloud-drawer");
      let start;
      drawer.addEventListener(
        "touchstart",
        (e) => {
          start = { x: e.touches[0].clientX, y: e.touches[0].clientY };
        },
        { passive: true },
      );
      drawer.addEventListener(
        "touchend",
        (e) => {
          if (!start) return;
          const end = e.changedTouches[0];
          if (
            end.clientX - start.x < -60 &&
            Math.abs(end.clientY - start.y) < 50
          )
            closeModal();
          start = null;
        },
        { passive: true },
      );
    },
    { drawer: true },
  );
}
function go(next) {
  if (!titles[next]) return;
  screen = next;
  query = "";
  searchOpen = false;
  history.replaceState(null, "", `#${next}`);
  closeModal();
  render();
  window.scrollTo({ top: 0 });
}
function chart(kind = "requests") {
  const cost = kind === "cost",
    week = chartPeriod === "7d";
  const points = cost
    ? "M35 128C60 124 60 120 84 125S120 112 148 104S180 114 206 96S244 110 270 77S308 91 334 61S369 81 393 49S428 56 454 25"
    : week
      ? "M35 124C70 121 79 100 105 106S151 70 180 82S225 120 256 90S302 39 329 50S373 81 401 59S437 25 454 22"
      : "M35 126C53 117 60 115 77 121S96 111 115 113S142 76 162 96S187 118 211 91S233 108 256 95S279 62 302 69S323 105 348 68S379 33 399 50S431 34 454 25";
  return `<svg class="chart" viewBox="0 0 480 180" preserveAspectRatio="none" role="img" aria-label="${cost ? "示例每日费用趋势" : "示例请求量趋势"}"><defs><linearGradient id="area-${kind}" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#b5cd93" stop-opacity=".25"/><stop offset="1" stop-color="#b5cd93" stop-opacity="0"/></linearGradient></defs>${[30, 70, 110, 150].map((y, i) => `<path d="M35 ${y}H465" stroke="#edf0e7" stroke-dasharray="3 5"/><text x="0" y="${y + 3}">${cost ? ["$8", "$6", "$4", "$2"][i] : ["80k", "60k", "40k", "20k"][i]}</text>`).join("")}<path d="${points}L454 150H35Z" fill="url(#area-${kind})"/><path d="${points}" fill="none" stroke="#64834a" stroke-width="2.2"/><circle cx="454" cy="${cost ? 25 : week ? 22 : 25}" r="4" fill="#678348" stroke="white" stroke-width="2"/>${["01", "06", "12", "18", "24", "30"].map((d, i) => `<text x="${35 + i * 83}" y="174">${week ? ["01", "02", "03", "04", "05", "07"][i] : d} ${cost ? "Sep" : "Oct"}</text>`).join("")}</svg>`;
}
function network() {
  let dots = "";
  for (let row = 0; row < 12; row++)
    for (let col = 0; col < 25; col++) {
      const x = 22 + col * 10,
        y = 22 + row * 9;
      const left =
        (col > 1 && col < 8 && row < 6) ||
        (col > 6 && col < 11 && row > 5 && row < 11);
      const right =
        (col > 12 && col < 21 && row > 1 && row < 7) ||
        (col > 13 && col < 17 && row > 5 && row < 10) ||
        (col > 20 && col < 24 && row > 8 && row < 11);
      if (left || right)
        dots += `<circle cx="${x}" cy="${y}" r="1.5" fill="#c4d1b5"/>`;
    }
  return `<svg class="network-visual" viewBox="0 0 290 150" aria-label="全球边缘网络示意图" role="img">${dots}<path d="M78 52Q147-3 192 56 M78 52Q114 140 225 110 M192 56Q247 40 225 110" fill="none" stroke="#a6bf8c" stroke-width=".7" stroke-dasharray="3 3"/>${[
    [78, 52],
    [192, 56],
    [225, 110],
    [147, 57],
  ]
    .map(
      ([x, y]) =>
        `<circle cx="${x}" cy="${y}" r="9" fill="#b4cb9430"/><circle cx="${x}" cy="${y}" r="3" fill="#7a9b5a"/>`,
    )
    .join(
      "",
    )}<rect x="169" y="69" width="64" height="18" rx="6" fill="white"/><text x="177" y="81" fill="#7c8a6b" font-size="8" font-family="sans-serif">Singapore · 24ms</text></svg>`;
}
function resourceRow(r) {
  return `<button class="resource-row" style="width:100%;text-align:left" data-detail="${esc(r.name)}"><span class="resource-icon ${r.type === "R2" ? "orange" : r.type === "D1" ? "blue" : ""}">${icon(r.icon)}</span><span class="row-content"><strong>${esc(r.name)}</strong><small>${r.type} · ${r.spec}</small></span><span class="row-end">${r.usage}<small>${r.unit}</small></span>${icon("right")}</button>`;
}
function overview() {
  const empty = account === "sandbox";
  return `${heading("YOUR CLOUD, AT A GLANCE", "所有云，一手掌握。", `${acctLabel()} · ${empty ? "尚未发现资源" : "一切运行正常，今天也很从容。"}`, btn("连接云账号", 'data-goto="accounts"', "plus", true))}<div class="grid dashboard-top"><section class="card spend-card"><div class="label">本月费用预估 <span class="tiny-pill" style="margin-left:8px;font-size:8px">USD</span></div><button class="spend-detail mobile-only" data-goto="billing" aria-label="查看费用">${icon("arrow")}</button><div class="spend-number"><span>$</span>${empty ? "0.00" : "48.62"}</div><div class="spend-meta"><span class="tiny-pill">${empty ? "待同步" : "↘ 较上月同期 12.8%"}</span><span>${empty ? "请先创建资源" : "预算已使用 48.6%"}</span></div></section><section class="card"><div class="stat-head">云资源 <span class="stat-icon">${icon("box")}</span></div><div class="stat-value">${resourceList().length}<span>个资源</span></div><div class="stat-foot"><i class="live-dot"></i>${empty ? "等待发现资源" : "全部运行正常"}</div></section><section class="card"><div class="stat-head">我的域名 <span class="stat-icon">${icon("globe")}</span></div><div class="stat-value">${domainList().length}<span>个域名</span></div><div class="stat-foot"><i class="live-dot ${empty ? "" : "orange-dot"}"></i>${empty ? "暂无域名" : "1 个即将到期"}</div></section></div><div class="quick-actions">${[
    ["globe", "购买域名", "buy"],
    ["plus", "创建资源", "create"],
    ["card", "费用明细", "billing"],
    ["clock", "操作记录", "jobs"],
  ]
    .map(
      ([ic, l, n]) =>
        `<button class="quick-action" ${n === "create" ? 'data-action="create-resource"' : `data-goto="${n}"`}><span>${icon(ic)}</span>${l}</button>`,
    )
    .join("")}</div>${
    empty
      ? '<div class="card empty">开发沙箱暂无资源。使用「创建资源」体验添加流程。</div>'
      : `<div class="grid dashboard-middle"><section class="card"><div class="card-title"><h2>流量概览</h2><div class="chart-header"><span class="chart-legend">请求量</span><div class="period-selector"><button data-period="7d" class="${chartPeriod === "7d" ? "active" : ""}">7 天</button><button data-period="30d" class="${chartPeriod === "30d" ? "active" : ""}">30 天</button></div></div></div>${chart()}<div class="chart-values"><div><strong>${chartPeriod === "7d" ? "62.4 万" : "250 万"}</strong><small>总请求量</small></div><div><strong>99.98<span style="font-size:12px">%</span></strong><small>请求成功率</small></div><div><strong>24 <span style="font-size:12px">ms</span></strong><small>平均响应时间</small></div></div></section><section class="card network-card"><div class="card-title"><h2>全球边缘网络</h2><span class="label" style="font-size:8px">LIVE / DEMO</span></div>${network()}<div class="network-footer"><div><strong>全球分布</strong><small>Worker 请求路由示意</small></div><span class="network-badge"><i class="live-dot"></i>运行正常</span></div></section></div><div class="grid dashboard-bottom"><section class="card"><div class="card-title"><h2>常用资源</h2><button class="text-btn" data-goto="resources">查看全部 ${icon("arrow")}</button></div>${resourceList().slice(0, 3).map(resourceRow).join("")}</section><section class="card activity-card"><div class="card-title"><h2>最近活动</h2><button class="text-btn" data-goto="jobs">${icon("arrow")}</button></div>${jobs
          .filter((j) => j.account === account)
          .slice(0, 3)
          .map(
            (j) =>
              `<div class="activity-row"><span class="activity-symbol">${icon("check")}</span><span class="row-content"><strong>${esc(j.name)}</strong><small>${esc(j.description)}</small></span><small>${j.time}</small></div>`,
          )
          .join("")}</section></div>`
  }<p class="screen-note mobile-only">演示数据 · 上次同步 1 分钟前</p>`;
}
function domainsScreen() {
  return `${heading("DOMAINS & DNS", "你的每一个好名字。", "注册、续费与解析，都在这里。", btn("购买域名", 'data-goto="buy"', "plus"))}<div class="mini-stat"><div class="card"><small>全部域名</small><strong>${domainList().length}</strong></div><div class="card"><small>Cloudflare 注册</small><strong>${domainList().filter((d) => d.registrar).length}</strong></div><div class="card"><small>即将到期</small><strong style="color:#d49b6d">${domainList().filter((d) => d.status === "即将到期").length}</strong></div></div><div class="tabs">${[
    ["all", "全部域名"],
    ["registered", "已注册"],
    ["expiry", "即将到期"],
  ]
    .map(
      ([id, l]) =>
        `<button data-domain-tab="${id}" class="${domainTab === id ? "active" : ""}">${l}</button>`,
    )
    .join(
      "",
    )}</div><div class="split"><section class="card" style="padding-top:10px"><div id="domain-table">${domainTable()}</div></section><aside class="card"><div class="card-title"><h2>域名健康</h2>${icon("shield")}</div><div class="info-list"><div class="info-line"><span>DNS 服务</span><strong><i class="live-dot"></i>正常</strong></div><div class="info-line"><span>SSL 证书</span><strong>有效</strong></div><div class="info-line"><span>待处理事项</span><strong style="color:#c69160">1 个域名待续费</strong></div></div><div class="callout warn section-space">${icon("clock")}<div><strong>studio.design 即将到期</strong>14 天后到期。打开官方控制台查看续费选项。<br><a class="text-btn" href="https://dash.cloudflare.com/" target="_blank" rel="noopener">前往控制台 ${icon("external")}</a></div></div><p class="screen-note">注册商与 DNS 托管独立显示，第三方注册域名也可管理解析。</p></aside></div>`;
}
function domainTable() {
  let list = domainList().filter(
    (d) =>
      (domainTab === "all" ||
        (domainTab === "registered" && d.registrar) ||
        (domainTab === "expiry" && d.status === "即将到期")) &&
      d.name.includes(query.toLowerCase()),
  );
  return list.length
    ? `<table class="table"><thead><tr><th>域名 / 套餐</th><th>状态</th><th class="expiry">到期时间</th><th></th></tr></thead><tbody>${list.map((d) => `<tr><td><button class="domain-name" data-domain="${esc(d.name)}"><span class="resource-icon">${icon("globe")}</span><span style="text-align:left"><strong>${esc(d.name)}</strong><small>${d.plan} · ${d.registrar ? "Cloudflare 注册" : "第三方注册"}</small></span></button></td><td><span class="badge ${d.status === "正常" ? "" : "warn"}">${d.status}</span></td><td class="expiry" style="color:#8b9582">${d.expires}</td><td><button class="icon-button" data-domain="${esc(d.name)}" aria-label="管理 ${esc(d.name)}">${icon("right")}</button></td></tr>`).join("")}</tbody></table>`
    : '<div class="empty">没有符合条件的域名</div>';
}
function dnsScreen() {
  const list = records.filter(
    (r) =>
      r.domain === selectedDomain &&
      (!query ||
        [r.name, r.type, r.content].some((value) =>
          value.toLowerCase().includes(query.toLowerCase()),
        )),
  );
  return `${detailHeading(selectedDomain, "Cloudflare DNS · 解析正常", "已激活", "domains", "globe")}<div class="tabs"><button class="active" data-action="dns-tab">DNS 解析</button><button data-action="domain-security">安全与证书</button><button data-action="domain-registration">注册信息</button></div><div class="split"><section class="card"><div class="card-title"><h2>解析记录 <span style="color:#99a28e;font-size:11px;margin-left:5px">${list.length}</span></h2><button class="text-btn" data-action="dns-new">${icon("plus")}添加记录</button></div><div class="dns-records">${list.length ? list.map((r) => `<button class="dns-record" data-record="${r.id}" style="text-align:left"><span class="dns-record-top"><span class="record-type">${r.type}</span><span style="color:#919c87">${icon("more")}</span></span><strong>${esc(r.name === "@" ? selectedDomain : r.name + "." + selectedDomain)}</strong><div class="content">${esc(r.content)}</div><div class="record-footer"><span class="proxy-label" style="${r.proxy ? "" : "color:#8c9782"}">${icon("cloud")}${r.proxy ? "已代理" : "仅 DNS"}${r.priority !== undefined ? " · 优先级 " + r.priority : ""}</span><span>TTL ${r.ttl}</span></div></button>`).join("") : query ? '<div class="empty">没有符合条件的解析记录</div>' : '<div class="empty">暂无解析，添加第一条记录。</div>'}</div><p class="screen-note">点击记录编辑。保存前将展示变更内容。</p></section><aside class="card"><div class="card-title"><h2>域名设置</h2>${icon("sliders")}</div><div class="info-list"><div class="info-line"><span>DNSSEC</span><strong>已启用</strong></div><div class="info-line"><span>SSL / TLS</span><strong>Full (strict)</strong></div><div class="info-line"><span>Cloudflare 代理</span><strong>按记录配置</strong></div></div><div class="callout section-space">${icon("info")}<div><strong>流量经过 Cloudflare</strong>开启代理的记录会使用 Cloudflare 的缓存与安全能力。MX / TXT 记录保持仅 DNS。</div></div></aside></div>`;
}
function buyScreen() {
  return `${compactHeading("购买域名", "", "domains")}<div class="split"><section class="card"><div class="card-title"><h2>${purchaseResults ? esc(purchaseKeyword) + " 的搜索结果" : "推荐域名"}</h2><span class="badge neutral">示例报价 · USD</span></div><div id="purchase-results">${purchaseResultHTML()}</div></section><aside class="card"><div class="card-title"><h2>买之前，先了解</h2>${icon("info")}</div><div class="info-list"><div class="info-line"><span>注册商</span><strong>Cloudflare Registrar</strong></div><div class="info-line"><span>账户</span><strong>${acctLabel()}</strong></div><div class="info-line"><span>自动续费</span><strong>默认关闭，可主动开启</strong></div><div class="info-line"><span>隐私保护</span><strong>按后缀支持情况提供</strong></div></div><div class="callout section-space">${icon("shield")}<div><strong>确认后再购买</strong>先验证可用性与最新价格，再确认支付。注册成功后不可退款。</div></div><p class="screen-note">首次购买需在官方控制台配置付款方式、注册联系人并接受注册协议。受限后缀使用官方入口。</p><a href="https://dash.cloudflare.com/" class="text-btn" target="_blank" rel="noopener">设置付款与注册信息 ${icon("external")}</a></aside></div>`;
}
function purchaseResultHTML() {
  const rs = purchaseResults || [
    { name: "hello-flarego.com", price: "10.44", available: true },
    { name: "hello-flarego.dev", price: "12.00", available: true },
    { name: "hello-flarego.app", price: "14.00", available: true },
    { name: "flarego.com", price: "—", available: false },
  ];
  return rs
    .map(
      (r, i) =>
        `<div class="domain-result"><span class="resource-icon">${icon("globe")}</span><span class="row-content"><strong>${esc(r.name)}${i === 0 ? '<span class="result-star desktop-only">推荐</span>' : ""}</strong><small>${r.available ? "可注册 · 示例报价" : "已被注册"}</small></span><span class="domain-price">${r.available ? "$" + r.price : "—"}<small>${r.available ? "首年 / 续费 $" + r.price : ""}</small></span><button class="btn ${r.available ? "soft" : "secondary"}" data-purchase="${esc(r.name)}" data-price="${r.price}" ${r.available ? "" : "disabled"}>${r.available ? "选择" : "不可用"}</button></div>`,
    )
    .join("");
}
function resourcesScreen() {
  return `${compactHeading("云资源", btn("创建资源", 'data-action="create-resource"', "plus"))}<div class="tabs">${[
    ["all", "全部"],
    ["edge", "边缘计算"],
    ["storage", "存储"],
    ["database", "数据库"],
    ["compute", "计算节点"],
  ]
    .map(
      ([id, l]) =>
        `<button class="${resourceTab === id ? "active" : ""}" data-resource-tab="${id}">${l}</button>`,
    )
    .join(
      "",
    )}</div><div id="resource-tiles">${resourceTiles()}</div><p class="screen-note">计算节点预留 VM / 容器类型；Cloudflare 首期以 Workers 为主。全部为演示资源。</p>`;
}
function resourceTiles() {
  const list = resourceList().filter(
    (r) =>
      (resourceTab === "all" || resourceTab === r.kind) &&
      r.name.includes(query.toLowerCase()),
  );
  if (resourceTab === "compute")
    return `<div class="card empty">${icon("server")}<h2 style="margin-top:14px">计算能力各有不同</h2><p style="margin:8px 0 18px;font-size:12px">Cloudflare Containers 规划接入；VM 实例将在 AWS 等适配器中提供。</p><button class="btn secondary" data-goto="accounts">查看云账号</button></div>`;
  return `<div class="resource-grid">${list.length ? list.map((r) => `<button class="card resource-tile" data-detail="${esc(r.name)}"><span class="resource-icon ${r.type === "R2" ? "orange" : r.type === "D1" ? "blue" : ""}">${icon(r.icon)}</span><span class="tile-identity"><h3 title="${esc(r.name)}">${esc(r.name)}</h3><span class="tile-meta">${r.type}<span class="tile-health"><i class="live-dot"></i>正常</span></span></span><span class="tile-usage"><strong>${r.usage}</strong><small>${r.unit}</small></span>${icon("right")}</button>`).join("") : '<div class="card empty">没有符合条件的资源</div>'}</div>`;
}
let selectedResource = "api-gateway";
function workerScreen() {
  return `${detailHeading(selectedResource, `Workers · ${currentVersion} · 全球边缘`, "运行中", "resources", "bolt")}<div class="tabs"><button class="active" data-action="worker-overview">概览</button><button data-action="versions">部署版本</button><button data-action="worker-bindings">资源绑定</button></div><div class="mini-stat"><div class="card"><small>请求 / 本月</small><strong>182<span style="font-size:12px"> 万</span></strong></div><div class="card"><small>错误率</small><strong>0.02<span style="font-size:12px">%</span></strong></div><div class="card"><small>CPU / 平均</small><strong>8<span style="font-size:12px"> ms</span></strong></div></div><div class="split"><section class="card"><div class="card-title"><h2>请求趋势</h2><span class="badge neutral">示例 · 30 天</span></div>${chart()}<div class="card-title section-space"><h2>当前部署</h2><span class="badge">${currentVersion}</span></div><div class="info-list"><div class="info-line"><span>部署时间</span><strong>今天 09:03</strong></div><div class="info-line"><span>流量分配</span><strong>100% 当前版本</strong></div><div class="info-line"><span>触发方式</span><strong>HTTP / Cron</strong></div></div><button class="btn secondary full section-space" data-action="versions">${icon("refresh")}管理部署版本</button></section><aside class="card"><div class="card-title"><h2>资源绑定</h2>${icon("link")}</div>${[
    {
      name: "media-assets",
      type: "R2",
      icon: "box",
      usage: "ASSETS",
      unit: "绑定名",
      spec: "对象存储",
    },
    {
      name: "app-database",
      type: "D1",
      icon: "database",
      usage: "DB",
      unit: "绑定名",
      spec: "数据库",
    },
  ]
    .map(resourceRow)
    .join(
      "",
    )}<p class="screen-note">绑定关系使用厂商原生能力，资源目录保持统一。</p></aside></div>`;
}
function bucketScreen() {
  return `${detailHeading(selectedResource, "R2 · 对象存储 · Standard", "可用", "resources", "box", "orange")}<div class="mini-stat"><div class="card"><small>存储用量</small><strong>128.4<span style="font-size:10px"> GB</span></strong></div><div class="card"><small>对象数量</small><strong>8,420</strong></div><div class="card"><small>公开访问</small><strong style="font-size:18px">已关闭</strong></div></div><section class="card"><div class="card-title"><h2>对象与目录</h2><span class="badge neutral">只读演示</span></div>${[
    ["images/", "4,218 个对象"],
    ["videos/", "126 个对象"],
    ["documents/", "4,076 个对象"],
  ]
    .map(
      ([name, n]) =>
        `<button class="resource-row" style="width:100%;text-align:left" data-folder="${name}"><span class="resource-icon orange">${icon("folder")}</span><span class="row-content"><strong>${name}</strong><small>${n}</small></span>${icon("right")}</button>`,
    )
    .join(
      "",
    )}<div class="callout section-space">${icon("lock")}<div><strong>私有存储桶</strong>对象浏览、上传与签名下载是对象存储能力。大文件通过后端授权的临时链接传输。</div></div></section>`;
}
function billingScreen() {
  if (account === "sandbox")
    return `${heading("COSTS & INSIGHTS", "账单与费用", "开发沙箱 · 暂无可用账单数据")}<div class="card"><div class="empty">当前账号暂无费用明细</div><div class="callout">${icon("info")}<div><strong>费用未知不等于零费用</strong>等待厂商账单同步或补充 Billing Read 权限后显示实际金额。</div></div></div>`;
  return `${heading("COSTS & INSIGHTS", "每一份投入，都清晰。", "费用按币种独立汇总 · USD · 示例数据", btn("导出明细", 'data-action="export-billing"', "download", true))}<div class="bill-summary"><section class="card spend-card"><div class="label">本月费用预估 · 10 月</div><div class="spend-number"><span>$</span>48.62</div><div class="spend-meta"><span class="tiny-pill">↘ 较上月同期 12.8%</span><span>基于用量估算</span></div></section><section class="card budget-card"><div class="stat-head">月度预算 <button class="text-btn" data-action="budget">编辑</button></div><div class="big-number">$${budget.toFixed(2)}</div><div class="bar"><i style="width:${Math.min(100, (48.62 / budget) * 100)}%"></i></div><p class="screen-note">已使用 ${((48.62 / budget) * 100).toFixed(1)}% · 80% 时提醒</p></section><section class="card"><div class="stat-head">上月已结算</div><div class="big-number">$68.40</div><small>2026 年 9 月 · 示例账单</small><br><span class="badge" style="display:inline-block;margin-top:10px">已支付</span></section></div><div class="split"><section class="card"><div class="card-title"><h2>费用构成</h2><span class="badge neutral">10 月 · 预估</span></div><div class="breakdown">${[
    ["Workers", 23.5, 48.3, "#6a8451"],
    ["R2 存储", 14.72, 30.3, "#a6bd8c"],
    ["域名与订阅", 8.0, 16.5, "#d6c4a2"],
    ["D1 / KV", 2.4, 4.9, "#afbbc8"],
  ]
    .map(
      ([n, c, p, col]) =>
        `<div><div class="breakdown-top"><span><i class="live-dot" style="background:${col}"></i>${n}</span><strong>$${c.toFixed(2)} <small style="margin-left:10px">${p}%</small></strong></div><div class="bar"><i style="width:${p}%;background:${col}"></i></div></div>`,
    )
    .join(
      "",
    )}</div><div class="callout section-space">${icon("info")}<div><strong>预估与最终账单分开显示</strong>本月费用可能随用量调整，最终金额以云厂商账单为准。无费用 API 权限时显示「暂不可用」。</div></div></section><aside class="card"><div class="card-title"><h2>已结算账单</h2><a class="text-btn" href="https://dash.cloudflare.com/" target="_blank" rel="noopener">官方账单 ${icon("external")}</a></div>${[
    ["2026 年 9 月", "68.40"],
    ["2026 年 8 月", "72.18"],
    ["2026 年 7 月", "65.32"],
  ]
    .map(
      ([m, c]) =>
        `<button class="billing-row" data-invoice="${m}" data-amount="${c}" style="width:100%;text-align:left"><span class="resource-icon">${icon("card")}</span><span class="row-content"><strong>${m}</strong><small>USD · 已支付</small></span><span class="row-end">$${c}</span>${icon("right")}</button>`,
    )
    .join(
      "",
    )}<p class="screen-note">账单历史、用量、发票是独立能力，根据账号授权分别启用。</p></aside></div>`;
}
function accountsScreen() {
  return `${heading("ONE APP, MANY CLOUDS", "连接你的云世界。", "从 Cloudflare 开始，让每朵云都有自己的位置。")}<div class="connection-banner"><span class="resource-icon">${icon("shield")}</span><div><h2>按需授权，清楚掌握权限。</h2><p class="subtitle">先接入只读权限，需要修改资源时再启用写入。</p></div><button class="btn secondary" data-action="permissions">查看权限</button></div><div class="card-title"><h2>已连接</h2><small>1 个云厂商 · 2 个账号</small></div><section class="card account-tile">${cfLogo()}<div class="row-content"><h3>Cloudflare</h3><p class="account-status"><i class="live-dot"></i>${acctLabel()} · Token 连接</p><small style="font-size:9px">域名 · DNS · Workers · R2 · D1 · KV</small></div><button class="text-btn" data-action="permissions">管理 ${icon("right")}</button></section><div class="card-title section-space"><h2>更多云厂商</h2><span class="badge neutral">规划中</span></div><div class="account-grid">${[
    ["aws", "aws", "Amazon Web Services", "IAM 角色 / STS"],
    ["google", "G", "Google Cloud", "OAuth / 服务账号"],
    ["azure", "⊞", "Microsoft Azure", "Entra ID / 服务主体"],
    ["ali", "阿", "阿里云", "RAM 角色"],
    ["tencent", "腾", "腾讯云", "CAM 角色"],
  ]
    .map(
      ([id, l, n, sub]) =>
        `<section class="card account-tile"><span class="provider-logo ${id}">${l}</span><span class="row-content"><h3>${n}</h3><p class="account-status">${sub} · 后续接入</p></span><button class="text-btn" data-provider="${n}">了解 ${icon("right")}</button></section>`,
    )
    .join(
      "",
    )}<button class="card account-tile" data-action="connect"><span class="provider-logo">${icon("plus")}</span><span class="row-content" style="text-align:left"><h3>添加 Cloudflare 连接</h3><p class="account-status">绑定另一个账号或补充权限</p></span>${icon("right")}</button></div><div class="callout section-space">${icon("lock")}<div><strong>凭据仅交给可信后端</strong>App 只显示连接状态、权限范围与最后验证时间。此原型使用模拟连接，请勿输入真实 Token。</div></div>`;
}
function jobsScreen() {
  const labels = {
    succeeded: ["已完成", ""],
    running: ["执行中", "neutral"],
    failed: ["失败", "warn"],
    action_required: ["待处理", "warn"],
    unknown: ["待核对", "warn"],
  };
  return `${heading("ACTIVITY & OPERATIONS", "每一步，都有记录。", "查看变更进度、结果与需要处理的事项。")}<section class="card">${jobs.map((j, i) => (j.account === account ? `<button class="job-row" style="width:100%;text-align:left" data-job="${i}"><span class="resource-icon">${icon(j.icon)}</span><span class="row-content"><strong>${esc(j.name)}</strong><span class="job-meta"><span>${esc(j.description)}</span><span>${j.time}</span></span></span><span class="badge ${labels[j.state][1]}">${labels[j.state][0]}</span>${icon("right")}</button>` : "")).join("") || '<div class="empty">当前账号暂无操作记录</div>'}</section><p class="screen-note">操作接受成功不等于资源已生效。超时后先核对云端结果，避免重复提交。</p>`;
}
const views = {
  overview,
  domains: domainsScreen,
  dns: dnsScreen,
  buy: buyScreen,
  resources: resourcesScreen,
  worker: workerScreen,
  bucket: bucketScreen,
  billing: billingScreen,
  accounts: accountsScreen,
  jobs: jobsScreen,
};
function render() {
  document.body.dataset.screen = screen;
  renderShell();
  $("#main").innerHTML = views[screen]();
  document.title = `FlareGo · ${titles[screen]}`;
}
function closeModal() {
  if (activeTrap) {
    document.removeEventListener("keydown", activeTrap);
    activeTrap = null;
  }
  const prev = $("#overlay")._previous;
  $("#overlay").innerHTML = "";
  document.body.style.overflow = "";
  if (prev?.isConnected) prev.focus();
}
function modal(title, body, after, options = {}) {
  if (activeTrap) document.removeEventListener("keydown", activeTrap);
  const previous = document.activeElement;
  $("#overlay")._previous = previous;
  $("#overlay").innerHTML =
    `<div class="modal-backdrop ${options.drawer ? "drawer-backdrop" : ""}"><section class="modal ${options.drawer ? "cloud-drawer" : ""}" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="modal-header"><h2>${title}</h2><button class="icon-button" data-action="close-modal" aria-label="关闭">${icon("close")}</button></div>${body}</section></div>`;
  document.body.style.overflow = "hidden";
  activeTrap = (e) => {
    if (e.key === "Escape") {
      closeModal();
      return;
    }
    if (e.key === "Tab") {
      const list = [
        ...$(".modal").querySelectorAll(
          "button:not(:disabled),input:not(:disabled),select,a[href]",
        ),
      ].filter((el) => el.offsetParent !== null);
      const first = list[0],
        last = list.at(-1);
      if (e.shiftKey && document.activeElement === first) {
        last?.focus();
        e.preventDefault();
      } else if (!e.shiftKey && document.activeElement === last) {
        first?.focus();
        e.preventDefault();
      }
    }
  };
  document.addEventListener("keydown", activeTrap);
  ($(".modal input") || $(".modal button"))?.focus();
  if (after) after();
}
let toastTimer;
function toast(msg) {
  $("#toast").textContent = msg;
  $("#toast").classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $("#toast").classList.remove("visible"), 3500);
}
function addJob(name, description, iconName = "globe", state = "succeeded") {
  jobs.unshift({
    name,
    description,
    icon: iconName,
    state,
    time: "刚刚",
    account,
  });
}
function editDNS(id) {
  const r = records.find((r) => r.id === id),
    canProxy = (type) => ["A", "AAAA", "CNAME"].includes(type);
  let proxy = r?.proxy ?? true;
  modal(
    r ? "编辑解析记录" : "添加解析记录",
    `<form id="dns-form"><div class="form-row"><div class="form-field"><label for="dns-type">记录类型</label><select name="type" id="dns-type">${["A", "AAAA", "CNAME", "TXT", "MX"].map((t) => `<option ${r?.type === t ? "selected" : ""}>${t}</option>`).join("")}</select></div><div class="form-field"><label for="dns-name">主机记录</label><input class="input" id="dns-name" name="name" value="${esc(r?.name ?? "@")}" required maxlength="100" placeholder="@、www 或 api"></div></div><div class="form-field"><label for="dns-content">记录内容</label><input class="input mono" id="dns-content" name="content" value="${esc(r?.content ?? "")}" required maxlength="1024" placeholder="如 192.0.2.10"></div><div class="form-row"><div class="form-field"><label for="dns-ttl">TTL</label><select id="dns-ttl" name="ttl">${["自动", "300", "3600", "86400"].map((v) => `<option ${r?.ttl === v ? "selected" : ""}>${v}</option>`).join("")}</select></div><div class="form-field" id="priority-field" style="display:${r?.type === "MX" ? "grid" : "none"}"><label for="dns-priority">MX 优先级</label><input class="input" id="dns-priority" name="priority" type="number" min="0" max="65535" value="${r?.priority ?? 10}"></div></div><div class="toggle-line"><span>Cloudflare 代理<br><small>仅 A / AAAA / CNAME 支持</small></span><button type="button" class="switch ${proxy ? "on" : ""}" role="switch" aria-checked="${proxy}" id="proxy-switch" aria-label="Cloudflare 代理"></button></div><p class="form-error" id="dns-error"></p><div class="modal-actions"><button class="btn secondary" type="button" data-action="close-modal">取消</button><button class="btn" type="submit">预览变更 ${icon("arrow")}</button></div></form>`,
    () => {
      const update = () => {
        const allowed = canProxy($("#dns-type").value);
        $("#proxy-switch").disabled = !allowed;
        if (!allowed) proxy = false;
        $("#proxy-switch").classList.toggle("on", proxy);
        $("#proxy-switch").setAttribute("aria-checked", String(proxy));
        $("#priority-field").style.display =
          $("#dns-type").value === "MX" ? "grid" : "none";
      };
      update();
      $("#proxy-switch").onclick = () => {
        proxy = !proxy;
        update();
      };
      $("#dns-type").onchange = update;
      $("#dns-form").onsubmit = (e) => {
        e.preventDefault();
        const f = new FormData(e.target),
          type = f.get("type"),
          content = f.get("content").trim(),
          name = f.get("name").trim();
        if (
          (type === "A" && !/^(\d{1,3}\.){3}\d{1,3}$/.test(content)) ||
          (type === "A" && content.split(".").some((v) => +v > 255))
        ) {
          return ($("#dns-error").textContent = "请输入有效的 IPv4 地址。");
        }
        if (type === "AAAA" && !content.includes(":"))
          return ($("#dns-error").textContent = "请输入有效的 IPv6 地址。");
        if (!name || !content)
          return ($("#dns-error").textContent = "请填写主机记录和内容。");
        const draft = {
          id: r?.id ?? Date.now(),
          domain: selectedDomain,
          type,
          name,
          content,
          proxy,
          ttl: proxy ? "自动" : f.get("ttl"),
          ...(type === "MX" ? { priority: Number(f.get("priority")) } : {}),
        };
        modal(
          "确认 DNS 变更",
          `<div class="review-card"><h3>${esc(name)} · ${type}</h3><div class="info-list"><div class="info-line"><span>域名</span><strong>${esc(selectedDomain)}</strong></div><div class="info-line"><span>${r ? "原内容" : "操作"}</span><strong class="mono">${r ? esc(r.content) : "新增记录"}</strong></div><div class="info-line"><span>新内容</span><strong class="mono">${esc(content)}</strong></div><div class="info-line"><span>代理状态 / TTL</span><strong>${proxy ? "已代理" : "仅 DNS"} / ${draft.ttl}</strong></div></div></div><div class="callout">${icon("info")}<div><strong>变更将应用到 ${esc(acctLabel())}</strong>记录提交后，各地解析缓存可能需要时间更新。</div></div><div class="modal-actions"><button class="btn secondary" id="dns-edit-back">返回编辑</button><button class="btn" id="dns-confirm">确认保存</button></div>`,
          () => {
            $("#dns-edit-back").onclick = () => editDNS(id);
            $("#dns-confirm").onclick = () => {
              if (r) records = records.map((v) => (v.id === r.id ? draft : v));
              else records.push(draft);
              addJob(
                `${r ? "更新" : "添加"} ${name} 的 DNS 解析`,
                `${selectedDomain} · ${type}`,
              );
              closeModal();
              render();
              toast("记录已保存（演示），解析缓存将逐步更新。");
            };
          },
        );
      };
    },
  );
}
function scenarioOptions() {
  return `<div class="demo-options" aria-label="演示结果">${[
    ["success", "成功"],
    ["action_required", "需补充资料"],
    ["unknown", "网络中断"],
    ["failed", "域名不可用"],
  ]
    .map(
      ([id, l]) =>
        `<button type="button" class="${demoScenario === id ? "active" : ""}" data-scenario="${id}">${l}</button>`,
    )
    .join("")}</div>`;
}
function purchase(name, price) {
  const purchaseAccount = account;
  let autoRenew = false;
  modal(
    "确认购买域名",
    `<form id="purchase-form"><div class="review-card"><h3>${esc(name)}</h3><div class="info-list"><div class="info-line"><span>购买账号</span><strong>${acctLabel()}</strong></div><div class="info-line"><span>注册期限</span><strong>1 年</strong></div><div class="info-line"><span>首年费用 / 续费参考</span><strong>$${price} / $${price} USD</strong></div><div class="info-line"><span>付款方式</span><strong>账户默认付款方式</strong></div></div></div><div class="toggle-line"><span>自动续费<br><small>开启后将按续费时的价格扣款</small></span><button type="button" class="switch" role="switch" aria-checked="false" id="renew-switch" aria-label="自动续费"></button></div><div class="callout warn">${icon("info")}<div><strong>成功注册后不可退款</strong>确认前会重新检查可用性与价格，价格变化时需再次确认。</div></div><label class="check-line"><input type="checkbox" required>我已核对域名和费用，并同意注册条款。</label><small style="display:block;margin-top:16px;font-size:9px">原型体验 · 选择模拟结果</small>${scenarioOptions()}<div class="modal-actions"><button type="button" class="btn secondary" data-action="close-modal">取消</button><button type="submit" class="btn">确认购买 · $${price}</button></div><p class="screen-note">仅演示操作，不会注册域名或产生扣款。</p></form>`,
    () => {
      $("#renew-switch").onclick = () => {
        autoRenew = !autoRenew;
        $("#renew-switch").classList.toggle("on", autoRenew);
        $("#renew-switch").setAttribute("aria-checked", String(autoRenew));
      };
      $("#purchase-form").onsubmit = (e) => {
        e.preventDefault();
        const result = demoScenario;
        addJob(
          `注册 ${name}`,
          `Registrar · $${price} USD`,
          "globe",
          result === "success" ? "running" : result,
        );
        const job = jobs[0];
        job.registration = { name, price, autoRenew, account: purchaseAccount };
        modal(
          "域名注册任务",
          `<div class="success"><span class="success-icon">${icon(result === "success" ? "clock" : "info")}</span><h2>${result === "success" ? "正在注册你的域名" : result === "action_required" ? "需要补充注册信息" : result === "unknown" ? "连接中断，正在核对" : "域名已不可用"}</h2><p>${esc(name)}<br>${result === "success" ? "任务已提交，请稍等。" : result === "action_required" ? "请前往官方控制台完善联系人或付款方式。" : result === "unknown" ? "结果尚未确认，先查询注册状态，避免重复扣款。" : "实时检查发现域名已被注册，请选择其他名字。"}</p></div><button class="btn full" data-goto="jobs">查看任务记录</button>`,
          () => {
            if (result === "success")
              setTimeout(() => {
                job.state = "succeeded";
                domains.push({
                  name,
                  status: "正常",
                  plan: "Free",
                  expires: "2027.10.07",
                  registrar: true,
                  account: purchaseAccount,
                  autoRenew,
                });
                if (screen === "jobs") render();
                if (document.querySelector('[aria-label="域名注册任务"]'))
                  modal(
                    "注册成功",
                    `<div class="success"><span class="success-icon">${icon("check")}</span><h2>好名字，属于你了。</h2><p>${esc(name)}<br>示例注册已完成，开始添加第一条解析。</p></div><button class="btn full" id="setup-dns">配置 DNS ${icon("arrow")}</button>`,
                    () => {
                      $("#setup-dns").onclick = () => {
                        selectedDomain = name;
                        go("dns");
                      };
                    },
                  );
              }, 1300);
          },
        );
      };
    },
  );
}
function createResource() {
  modal(
    "创建云资源",
    `<form id="resource-form"><div class="form-field"><label for="resource-type">资源类型</label><select id="resource-type" name="type"><option value="R2">R2 · 对象存储桶</option><option value="Workers">Workers · 边缘函数</option><option value="D1">D1 · SQL 数据库</option><option value="KV">KV · 键值存储</option></select></div><div class="form-field"><label for="resource-name">资源名称</label><input id="resource-name" class="input" name="name" placeholder="如 my-media-assets" required pattern="[a-z0-9][a-z0-9-]{1,50}[a-z0-9]" maxlength="52"><small>3–52 位小写字母、数字或连字符</small></div><div class="form-field"><label>创建账号</label><div class="input">Cloudflare · ${acctLabel()}</div></div><div class="callout">${icon("lock")}<div><strong id="create-hint-title">默认私有访问</strong><span id="create-hint">存储桶创建后可配置公开域名、生命周期和访问规则。</span></div></div><div class="modal-actions"><button type="button" class="btn secondary" data-action="close-modal">取消</button><button type="submit" class="btn">创建资源</button></div><p class="form-error" id="resource-error"></p><p class="screen-note">演示创建。实际收费与可用配置以厂商为准。</p></form>`,
    () => {
      $("#resource-type").onchange = () => {
        const type = $("#resource-type").value;
        $("#create-hint-title").textContent =
          type === "Workers" ? "创建最小示例 Worker" : "按产品默认配置创建";
        $("#create-hint").textContent =
          type === "Workers"
            ? "首次创建使用示例代码；正式部署需上传构建产物并设置绑定。"
            : "完成后可进入资源详情调整配置。";
      };
      $("#resource-form").onsubmit = (e) => {
        e.preventDefault();
        const f = new FormData(e.target),
          name = f.get("name"),
          type = f.get("type");
        if (resourceList().some((r) => r.name === name))
          return ($("#resource-error").textContent =
            "当前账号中已存在同名资源。");
        resources.push({
          name,
          type,
          kind:
            type === "Workers"
              ? "edge"
              : type === "D1"
                ? "database"
                : "storage",
          icon:
            type === "Workers"
              ? "bolt"
              : type === "D1"
                ? "database"
                : type === "KV"
                  ? "server"
                  : "box",
          usage: type === "Workers" ? "0" : "0 B",
          unit: type === "Workers" ? "本月请求" : "已用存储",
          spec: type === "Workers" ? "全球边缘网络" : "新建资源",
          account,
        });
        addJob(
          `创建 ${name}`,
          `Cloudflare · ${type}`,
          type === "Workers" ? "bolt" : "box",
        );
        resourceTab = "all";
        go("resources");
        toast("资源已创建（演示）");
      };
    },
  );
}
function versions() {
  modal(
    "部署版本",
    `<p class="subtitle" style="margin-bottom:15px">${esc(selectedResource)} · 先检查版本，再切换流量。</p>${["v1.8.2", "v1.8.1", "v1.7.9"].map((v, i) => `<div class="resource-row"><span class="resource-icon">${icon("code")}</span><span class="row-content"><strong>${v}</strong><small>${["今天 09:03", "昨天 18:26", "10 月 4 日 10:12"][i]}</small></span>${v === currentVersion ? '<span class="badge">当前版本</span>' : `<button class="text-btn" data-version="${v}">切换此版本 ${icon("arrow")}</button>`}</div>`).join("")}<div class="callout section-space">${icon("info")}<div>切换部署会将流量指向所选代码版本。数据库和外部数据变更需要独立处理。</div></div>`,
  );
}
function showResource(name) {
  selectedResource = name;
  const r = resources.find((r) => r.name === name);
  if (!r) return;
  if (r.type === "Workers") go("worker");
  else if (r.type === "R2") go("bucket");
  else
    modal(
      `${r.type} · ${esc(name)}`,
      `<div class="info-list"><div class="info-line"><span>云账号</span><strong>${acctLabel()}</strong></div><div class="info-line"><span>用量</span><strong>${r.usage}</strong></div><div class="info-line"><span>状态</span><strong>可用</strong></div></div><div class="callout section-space">${icon("database")}<div>${r.type === "D1" ? "数据库查询、备份与恢复" : "键值浏览、读写与过期策略"}在原生产品模块中扩展。</div></div><a class="btn secondary full section-space" href="https://dash.cloudflare.com/" target="_blank" rel="noopener">前往官方控制台 ${icon("external")}</a>`,
    );
}
const actions = {
  "cloud-menu": openCloudMenu,
  "page-search": openPageSearch,
  "close-page-search": closePageSearch,
  "clear-page-search": () => {
    query = "";
    $("#page-search-panel input").value = "";
    refreshSearchResults();
    $("#page-search-panel input").focus();
  },
  "close-modal": closeModal,
  "dns-new": () => editDNS(null),
  "dns-tab": () => render(),
  "worker-overview": () => render(),
  "create-resource": createResource,
  versions,
  "switch-account": () =>
    modal(
      "选择 Cloudflare 账号",
      `<p class="subtitle" style="margin-bottom:15px">同一云厂商可以接入多个独立账号。</p>${[
        ["production", "个人生产账号", "4 个域名 · 6 个资源"],
        ["sandbox", "开发沙箱", "从空工作区开始"],
      ]
        .map(
          ([id, l, sub]) =>
            `<button class="search-result" data-account="${id}">${cfLogo()}<span style="flex:1">${l}<br><small>${sub}</small></span>${account === id ? icon("check") : icon("right")}</button>`,
        )
        .join(
          "",
        )}<button class="btn secondary full section-space" data-action="connect">${icon("plus")}添加新连接</button>`,
    ),
  permissions: () =>
    modal(
      "Cloudflare 连接与权限",
      `<div class="resource-row">${cfLogo()}<span class="row-content"><strong>${acctLabel()}</strong><small>最后验证：1 分钟前 · 演示连接</small></span><span class="badge">已连接</span></div><div class="permission-list">${[
        ["读取", "域名 / DNS / Workers / R2 / D1 / KV"],
        ["写入", "DNS / Workers / R2"],
        ["单独授权", "Registrar 购买 / Billing Read"],
      ]
        .map(([k, v]) => `<div>${icon("check")}<b>${k}</b> · ${v}</div>`)
        .join(
          "",
        )}</div><div class="callout">${icon("shield")}<div>权限按账号与 Zone 范围验证，无法访问的能力显示具体缺失权限。</div></div><div class="modal-actions"><button class="btn secondary" data-action="switch-account">切换账号</button><button class="btn" data-action="connect">更新连接</button></div>`,
    ),
  connect: () =>
    modal(
      "接入 Cloudflare",
      `<form id="connect-form"><div class="form-field"><label for="connection-name">连接名称</label><input class="input" name="name" id="connection-name" value="我的 Cloudflare" required maxlength="40"></div><div class="form-field"><label for="connection-token">API Token · 模拟输入</label><input class="input mono" name="token" id="connection-token" type="password" placeholder="填写 demo-token 即可体验" required autocomplete="off"></div><div class="permission-list">${icon("check")}先验证 Token，再发现账号与可访问资源<br>${icon("check")}只读起步，写入权限按需补充<br>${icon("check")}权限不足不会影响已授权的模块</div><p class="form-error" id="connection-error"></p><button class="btn full section-space" type="submit">验证并连接 ${icon("arrow")}</button><p class="screen-note">请仅输入 demo-token。此原型不连接云 API、不保存凭据。</p></form>`,
      () => {
        $("#connect-form").onsubmit = (e) => {
          e.preventDefault();
          const f = new FormData(e.target);
          if (f.get("token") !== "demo-token")
            return ($("#connection-error").textContent =
              "这是模拟连接，请使用 demo-token，勿填真实凭据。");
          modal(
            "连接验证完成",
            `<div class="success"><span class="success-icon">${icon("check")}</span><h2>${esc(f.get("name"))}</h2><p>已发现 2 个示例 Cloudflare 账号。<br>DNS / Workers / R2 等模块可以使用。</p></div><button class="btn full" data-action="switch-account">选择账号并开始</button>`,
          );
        };
      },
    ),
  budget: () =>
    modal(
      "设置月度预算",
      `<form id="budget-form"><div class="form-field"><label for="budget-amount">预算金额 · USD</label><input id="budget-amount" name="amount" class="input" type="number" min="1" max="1000000" step=".01" value="${budget}" required></div><div class="callout">${icon("bell")}<div><strong>达到 80% 时提醒</strong>预算用于提醒，不会自动停止资源或限制云厂商扣款。</div></div><button class="btn full section-space" type="submit">保存预算</button></form>`,
      () => {
        $("#budget-form").onsubmit = (e) => {
          e.preventDefault();
          budget = Number(new FormData(e.target).get("amount"));
          closeModal();
          render();
          toast("预算已更新（演示）");
        };
      },
    ),
  "export-billing": () => {
    const csv =
      "\uFEFF类型,产品,金额,币种,期间,来源\n预估,Workers,23.50,USD,2026-10,演示\n预估,R2,14.72,USD,2026-10,演示\n预估,域名与订阅,8.00,USD,2026-10,演示\n预估,D1/KV,2.40,USD,2026-10,演示\n";
    const url = URL.createObjectURL(
      new Blob([csv], { type: "text/csv;charset=utf-8" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download = "flarego-demo-costs-2026-10.csv";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast("已导出演示费用明细");
  },
  "domain-security": () =>
    modal(
      "安全与证书",
      `<div class="info-list"><div class="info-line"><span>域名</span><strong>${esc(selectedDomain)}</strong></div><div class="info-line"><span>SSL / TLS 模式</span><strong>Full (strict)</strong></div><div class="info-line"><span>证书</span><strong>有效 · 演示</strong></div><div class="info-line"><span>DNSSEC</span><strong>已启用 · 演示</strong></div></div><div class="callout section-space">${icon("shield")}<div>安全配置以 Zone 为范围，按能力与授权开放高级设置。</div></div>`,
    ),
  "domain-registration": () => {
    const d = domains.find((d) => d.name === selectedDomain);
    modal(
      "域名注册信息",
      `<div class="info-list"><div class="info-line"><span>域名</span><strong>${esc(selectedDomain)}</strong></div><div class="info-line"><span>注册商</span><strong>${d?.registrar ? "Cloudflare" : "第三方注册商"}</strong></div><div class="info-line"><span>到期时间</span><strong>${d?.expires ?? "待同步"}</strong></div><div class="info-line"><span>自动续费</span><strong>${d?.autoRenew ? "已开启" : "未开启"}</strong></div></div><div class="callout section-space">${icon("info")}<div>续费、转移与联系人修改根据账号能力开放。当前演示通过官方控制台处理。</div></div><a class="btn secondary full section-space" href="https://dash.cloudflare.com/" target="_blank" rel="noopener">前往官方控制台 ${icon("external")}</a>`,
    );
  },
  "worker-bindings": () =>
    modal(
      "Worker 资源绑定",
      `<div class="resource-row"><span class="resource-icon orange">${icon("box")}</span><span class="row-content"><strong>ASSETS → media-assets</strong><small>R2 Bucket</small></span></div><div class="resource-row"><span class="resource-icon blue">${icon("database")}</span><span class="row-content"><strong>DB → app-database</strong><small>D1 Database</small></span></div><p class="screen-note">示例绑定。修改绑定将形成新的部署变更。</p>`,
    ),
  search: () =>
    modal(
      "搜索你的云资源",
      `<div class="search-field">${icon("search")}<input id="global-search" placeholder="域名、资源名称或页面…" aria-label="全局搜索"></div><div id="global-results" class="section-space"></div>`,
      () => {
        const entries = [
          ...navs.map(([id, ic, label]) => ({ id, ic, label })),
          ...domainList().map((d) => ({
            domain: d.name,
            ic: "globe",
            label: d.name,
          })),
          ...resourceList().map((r) => ({
            resource: r.name,
            ic: r.icon,
            label: r.name,
          })),
        ];
        const update = () => {
          $("#global-results").innerHTML =
            entries
              .filter((e) =>
                e.label
                  .toLowerCase()
                  .includes($("#global-search").value.toLowerCase()),
              )
              .map(
                (e) =>
                  `<button class="search-result" ${e.id ? `data-goto="${e.id}"` : e.domain ? `data-domain="${esc(e.domain)}"` : `data-detail="${esc(e.resource)}"`}>${icon(e.ic)}${esc(e.label)}<small>${e.id ? "页面" : e.domain ? "域名" : "资源"}</small></button>`,
              )
              .join("") || '<div class="empty">没有找到匹配结果</div>';
        };
        $("#global-search").oninput = update;
        update();
      },
    ),
};
document.addEventListener("click", (e) => {
  const el = e.target.closest("button,a");
  if (!el) {
    if (e.target.classList.contains("modal-backdrop")) closeModal();
    return;
  }
  if (el.dataset.goto) {
    e.preventDefault();
    go(el.dataset.goto);
  } else if (el.dataset.action) {
    e.preventDefault();
    actions[el.dataset.action]?.();
  } else if (el.dataset.domain) {
    selectedDomain = el.dataset.domain;
    go("dns");
  } else if (el.dataset.record) {
    editDNS(Number(el.dataset.record));
  } else if (el.dataset.detail) {
    showResource(el.dataset.detail);
  } else if (el.dataset.period) {
    chartPeriod = el.dataset.period;
    render();
  } else if (el.dataset.domainTab) {
    domainTab = el.dataset.domainTab;
    render();
  } else if (el.dataset.resourceTab) {
    resourceTab = el.dataset.resourceTab;
    render();
  } else if (el.dataset.purchase) {
    purchase(el.dataset.purchase, el.dataset.price);
  } else if (el.dataset.scenario) {
    demoScenario = el.dataset.scenario;
    document
      .querySelectorAll("[data-scenario]")
      .forEach((b) =>
        b.classList.toggle("active", b.dataset.scenario === demoScenario),
      );
  } else if (el.dataset.account) {
    account = el.dataset.account;
    resourceTab = "all";
    domainTab = "all";
    const destination = ["dns", "buy"].includes(screen)
      ? "domains"
      : ["worker", "bucket"].includes(screen)
        ? "resources"
        : screen;
    go(destination);
    toast(`已切换至${acctLabel()}`);
  } else if (el.dataset.provider) {
    modal(
      el.dataset.provider,
      `<p class="subtitle">该厂商适配器规划中，当前无法接入。</p><div class="callout section-space">${icon("cloud")}<div>未来通过相同的账号、资源目录、操作任务与费用接口接入；保留该厂商独有的产品操作。</div></div><a class="btn secondary full section-space" href="architecture.html">查看适配器架构 ${icon("external")}</a>`,
    );
  } else if (el.dataset.version) {
    const version = el.dataset.version;
    modal(
      "确认切换部署",
      `<div class="review-card"><h3>${esc(selectedResource)}</h3><div class="info-list"><div class="info-line"><span>当前 → 目标版本</span><strong>${currentVersion} → ${version}</strong></div><div class="info-line"><span>流量分配</span><strong>100% 切换到目标版本</strong></div></div></div><button class="btn full" id="confirm-version">确认切换</button>`,
      () => {
        $("#confirm-version").onclick = () => {
          currentVersion = version;
          addJob(`切换 ${selectedResource} 部署`, version, "bolt");
          closeModal();
          render();
          toast("部署版本已切换（演示）");
        };
      },
    );
  } else if (el.dataset.invoice) {
    modal(
      el.dataset.invoice + " · 示例账单",
      `<div class="review-card"><div class="label">已结算 / USD / DEMO</div><div class="big-number">$${el.dataset.amount}</div><span class="badge">已支付</span></div><div class="info-list"><div class="info-line"><span>账单来源</span><strong>Cloudflare · 模拟账单</strong></div><div class="info-line"><span>付款状态</span><strong>已支付</strong></div></div><a class="btn secondary full section-space" href="https://dash.cloudflare.com/" target="_blank" rel="noopener">前往官方账单 ${icon("external")}</a>`,
    );
  } else if (el.dataset.job !== undefined) {
    showJob(Number(el.dataset.job));
  } else if (el.dataset.folder) {
    modal(
      el.dataset.folder + " · 示例对象",
      `<div class="resource-row"><span class="resource-icon orange">${icon("box")}</span><span class="row-content"><strong>example-${el.dataset.folder === "videos/" ? "video.mp4" : el.dataset.folder === "documents/" ? "document.pdf" : "image.webp"}</strong><small>示例对象 · 私有访问</small></span></div><div class="callout section-space">${icon("lock")}<div>正式版本可通过短期签名链接下载对象；原型不传输真实数据。</div></div>`,
    );
  }
});
function showJob(i) {
  const j = jobs[i];
  modal(
    "操作详情",
    `<h3>${esc(j.name)}</h3><p class="subtitle">${esc(j.description)}</p><div class="timeline"><div class="timeline-step"><span class="step-dot">${icon("check")}</span><span>校验账号、权限与输入<small>本地检查完成 · 演示</small></span></div><div class="timeline-step"><span class="step-dot">2</span><span>提交云厂商请求<small>保留请求标识和执行上下文</small></span></div><div class="timeline-step ${j.state === "succeeded" ? "" : "pending"}"><span class="step-dot">3</span><span>${j.state === "succeeded" ? "云端确认完成" : j.state === "unknown" ? "结果待核对" : j.state === "action_required" ? "等待补充信息" : j.state === "failed" ? "操作失败" : "查询执行进度"}<small>${j.state === "succeeded" ? "资源快照已更新" : "后续处理会保留同一个操作任务"}</small></span></div></div>${j.state === "unknown" ? '<button class="btn full" id="reconcile">查询云端结果</button>' : j.state === "action_required" ? '<a class="btn full" href="https://dash.cloudflare.com/" target="_blank" rel="noopener">前往官方控制台补充资料</a>' : j.state === "failed" ? '<button class="btn full" data-goto="buy">重新搜索域名</button>' : '<button class="btn secondary full" data-action="close-modal">完成</button>'}`,
    () => {
      if (j.state === "unknown")
        $("#reconcile").onclick = () => {
          j.state = "succeeded";
          if (
            j.registration &&
            !domains.some(
              (d) =>
                d.name === j.registration.name &&
                d.account === j.registration.account,
            )
          )
            domains.push({
              name: j.registration.name,
              status: "正常",
              plan: "Free",
              expires: "2027.10.07",
              registrar: true,
              account: j.registration.account,
              autoRenew: j.registration.autoRenew,
            });
          closeModal();
          render();
          toast("模拟核对完成，云端已注册成功。");
        };
    },
  );
}
document.addEventListener("input", (e) => {
  if (
    ["domain-search", "resource-search", "dns-search"].includes(e.target.id)
  ) {
    query = e.target.value;
    refreshSearchResults();
  }
});
document.addEventListener("submit", (e) => {
  if (e.target.id === "purchase-search") {
    e.preventDefault();
    const keyword = new FormData(e.target).get("keyword").toLowerCase();
    purchaseKeyword = keyword;
    purchaseResults = ["com", "dev", "app", "io"].map((t, i) => ({
      name: `${keyword}.${t}`,
      price: ["10.44", "12.00", "14.00", "39.00"][i],
      available: !domains.some((d) => d.name === `${keyword}.${t}`),
    }));
    searchOpen = false;
    render();
    $('#topbar [data-action="page-search"]')?.focus();
  }
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && searchOpen && !$(".modal")) {
    closePageSearch();
    return;
  }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    actions.search();
  }
});
render();

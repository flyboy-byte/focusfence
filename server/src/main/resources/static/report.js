// Focus Fence report page (D-023). The phone seals each report slice with a key the server never sees; the key
// reaches this browser only after the '#' of a link (never sent to a server) and is kept in localStorage. Here we
// open the slices with WebCrypto AES-GCM and merge them into one 7-day report. All output is built with
// textContent, never innerHTML: the report text comes from the phone, so it is treated as data.
'use strict';
(function () {
  var STORE = 'ff_report_keys';

  function loadKeys() {
    try { var v = JSON.parse(localStorage.getItem(STORE) || '[]'); return Array.isArray(v) ? v : []; } catch (e) { return []; }
  }
  function saveKey(k) {
    if (!/^[A-Za-z0-9_-]{43}$/.test(k)) return false;
    var ks = loadKeys().filter(function (x) { return x !== k; });
    ks.unshift(k);
    try { localStorage.setItem(STORE, JSON.stringify(ks.slice(0, 10))); } catch (e) { return false; }
    return true;
  }
  function b64u(s) {
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var bin = atob(s), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function el(tag, text, cls) {
    var e = document.createElement(tag);
    if (text != null) e.textContent = String(text);
    if (cls) e.className = cls;
    return e;
  }

  // A key in the address (#k=...) is saved, then removed from the address bar so it isn't bookmarked or shared.
  var fromHash = new URLSearchParams(location.hash.slice(1)).get('k');
  var saved = fromHash ? saveKey(fromHash) : false;
  if (fromHash) history.replaceState(null, '', location.pathname + location.search);

  var page = document.body.getAttribute('data-page');
  if (page === 'key') {
    var st = document.getElementById('keystatus');
    if (st) st.textContent = saved ? 'Key saved in this browser.' : 'No valid key in this link. Ask for the link again.';
    return;
  }
  if (page === 'join') {
    // Chat apps open links in their own browser, and the confirmation email often opens in another. Offer the key as a
    // link the partner can copy into the browser they'll read reports in. Shown only here, only from the link they hold.
    var js = document.getElementById('keystatus');
    if (js && saved) {
      js.appendChild(el('p', 'The key that unlocks the report is saved in this browser. If you will read reports in a different browser (for example, this link opened inside a chat app), copy this link and open it there:'));
      var inp = el('input');
      inp.value = location.origin + '/key#k=' + fromHash;
      inp.readOnly = true;
      inp.style.width = '100%';
      inp.addEventListener('focus', function () { inp.select(); });
      js.appendChild(inp);
    }
    return;
  }
  if (page !== 'report') return;

  var box = document.getElementById('report');
  function show(nodes) { box.textContent = ''; nodes.forEach(function (n) { box.appendChild(n); }); }

  function pairs(s) {
    var out = {};
    String(s || '').split('\n').forEach(function (line) {
      var i = line.lastIndexOf('\t');
      if (i <= 0) return;
      var n = parseInt(line.slice(i + 1), 10);
      if (!isNaN(n)) out[line.slice(0, i)] = (out[line.slice(0, i)] || 0) + n;
    });
    return out;
  }
  function addInto(acc, s) { var p = pairs(s); for (var k in p) acc[k] = (acc[k] || 0) + p[k]; }
  function top(acc, n) {
    return Object.keys(acc).map(function (k) { return [k, acc[k]]; })
      .sort(function (a, b) { return b[1] - a[1]; }).slice(0, n);
  }

  function merge(digests) {
    var m = { off: 0, missing: 0, lookups: 0, blocked: 0, enforce: 0, flagged: 0, events: [],
      lists: { cats: {}, cat_hosts: {}, apps_min: {}, sites: {}, top_blocked: {} }, from: null, to: null };
    digests.sort(function (a, b) { return (a.from || 0) - (b.from || 0); }).forEach(function (d) {
      m.off += +d.off_min || 0; m.missing += +d.missing || 0; m.lookups += +d.lookups || 0;
      m.blocked += +d.blocked || 0; m.enforce += +d.enforce || 0; m.flagged += +d.flagged_shots || 0;
      String(d.events || '').split('\n').forEach(function (e) { if (e.trim()) m.events.push(e); });
      for (var k in m.lists) addInto(m.lists[k], d[k]);
      if (m.from == null || d.from < m.from) m.from = d.from;
      if (m.to == null || d.to > m.to) m.to = d.to;
    });
    m.events = m.events.slice(-50);
    return m;
  }

  function table(title, acc) {
    var rows = top(acc, 15);
    if (!rows.length) return [];
    var t = el('table');
    rows.forEach(function (r) { var tr = el('tr'); tr.appendChild(el('td', r[0])); tr.appendChild(el('td', r[1])); t.appendChild(tr); });
    return [el('h2', title), t];
  }

  function render(m, n) {
    var out = [];
    out.push(m.off === 0 ? el('p', 'Protection was on the whole time.', 'ok') : el('p', 'Protection was OFF for ' + m.off + ' minutes.', 'bad'));
    if (m.missing > 0) out.push(el('p', m.missing + ' records are missing from the log (deleted or lost).', 'bad'));
    if (m.events.length) {
      out.push(el('p', 'Changes:'));
      var ul = el('ul');
      m.events.forEach(function (e) { ul.appendChild(el('li', e)); });
      out.push(ul);
    }
    var line = m.lookups + ' lookups · ' + m.blocked + ' blocked';
    if (m.flagged > 0) line += ' · ' + m.flagged + ' flagged screenshots (kept on the phone)';
    out.push(el('p', line, 'muted'));
    if (m.from && m.to) out.push(el('p', 'Covers ' + new Date(m.from).toLocaleString() + ' to ' + new Date(m.to).toLocaleString() + ' (' + n + ' slices).', 'muted'));
    out = out.concat(table('Categories', m.lists.cats), table('Category sites', m.lists.cat_hosts),
      table('App time (minutes)', m.lists.apps_min), table('Browser sites', m.lists.sites), table('Top blocked', m.lists.top_blocked));
    show(out);
  }

  async function open(keys, deviceId, reports) {
    var aad = new TextEncoder().encode('ff-report|' + deviceId);
    var imported = [];
    for (var i = 0; i < keys.length; i++) {
      try { imported.push(await crypto.subtle.importKey('raw', b64u(keys[i]), 'AES-GCM', false, ['decrypt'])); } catch (e) {}
    }
    var good = null, digests = [];
    for (var r = 0; r < reports.length; r++) {
      var raw = b64u(reports[r].blob);
      var tryKeys = good ? [good] : imported;
      for (var j = 0; j < tryKeys.length; j++) {
        try {
          var plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(0, 12), additionalData: aad }, tryKeys[j], raw.slice(12));
          digests.push(JSON.parse(new TextDecoder().decode(plain)));
          good = tryKeys[j];
          break;
        } catch (e) {}
      }
    }
    return digests;
  }

  fetch(location.pathname.replace(/\/$/, '') + '/data', { cache: 'no-store', credentials: 'omit' })
    .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
    .then(async function (data) {
      var reports = data.reports || [];
      if (!reports.length) {
        show([el('p', 'No report yet. The phone sends one every hour, once it runs Focus Fence AC 2.11 or newer.', 'muted')]);
        return;
      }
      var keys = loadKeys();
      if (!window.crypto || !crypto.subtle) {
        show([el('p', 'This browser cannot open encrypted reports. Open the link in Chrome, Firefox or Safari.', 'bad')]);
        return;
      }
      var digests = await open(keys, data.deviceId, reports);
      if (!digests.length) {
        show([el('p', 'This report is end-to-end encrypted and this browser does not have the key.', 'bad'),
          el('p', 'Open the invite link (or a "key link") the phone\'s owner sent you, in this same browser, then come back here. ' +
            'Links opened inside a chat or email app often use a separate browser.', 'muted')]);
        return;
      }
      render(merge(digests), digests.length);
    })
    .catch(function () { show([el('p', 'Could not load the report. Try again later.', 'bad')]); });
})();

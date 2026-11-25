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
    var m = { off: 0, missing: 0, net: 0, lookups: 0, blocked: 0, enforce: 0, flagged: 0, events: [],
      lists: { cats: {}, cat_hosts: {}, apps_min: {}, sites: {}, top_blocked: {} }, from: null, to: null };
    digests.sort(function (a, b) { return (a.from || 0) - (b.from || 0); }).forEach(function (d) {
      m.off += +d.off_min || 0; m.missing += +d.missing || 0; m.net += +d.net_trouble || 0; m.lookups += +d.lookups || 0;
      m.blocked += +d.blocked || 0; m.enforce += +d.enforce || 0; m.flagged += +d.flagged_shots || 0;
      // A change has no time of its own: it happened within its slice, so it is shown under that slice's day.
      String(d.events || '').split('\n').forEach(function (e) { if (e.trim()) m.events.push({ t: +d.to || 0, text: e }); });
      for (var k in m.lists) addInto(m.lists[k], d[k]);
      if (m.from == null || d.from < m.from) m.from = d.from;
      if (m.to == null || d.to > m.to) m.to = d.to;
    });
    m.events = m.events.slice(-200);
    return m;
  }

  // A table you can sort (tap a heading) and filter (type), instead of one long list.
  function sortable(title, acc, unit) {
    var rows = Object.keys(acc).map(function (k) { return [k, acc[k]]; });
    var wrap = el('div');
    if (!rows.length) { wrap.appendChild(el('p', 'Nothing in this period.', 'muted')); return wrap; }
    var by = 1, desc = true, q = '';
    var input = el('input', null, 'filter'); input.placeholder = 'Filter ' + title.toLowerCase();
    var t = el('table', null, 'sort');
    function draw() {
      t.textContent = '';
      var head = el('tr');
      [[title, 0, ''], [unit, 1, 'n']].forEach(function (h) {
        var th = el('th', h[0] + (by === h[1] ? (desc ? ' \u2193' : ' \u2191') : ''), h[2]);
        th.addEventListener('click', function () { if (by === h[1]) desc = !desc; else { by = h[1]; desc = h[1] === 1; } draw(); });
        head.appendChild(th);
      });
      t.appendChild(head);
      rows.filter(function (r) { return !q || r[0].toLowerCase().indexOf(q) >= 0; })
        .sort(function (a, b) { var c = by === 1 ? a[1] - b[1] : a[0].localeCompare(b[0]); return desc ? -c : c; })
        .slice(0, 100).forEach(function (r) { var tr = el('tr'); tr.appendChild(el('td', r[0])); tr.appendChild(el('td', r[1], 'n')); t.appendChild(tr); });
    }
    input.addEventListener('input', function () { q = input.value.trim().toLowerCase(); draw(); });
    draw();
    if (rows.length > 8) wrap.appendChild(input);
    wrap.appendChild(t);
    return wrap;
  }

  function seg(labels, on, pick) {
    var bar = el('div', null, 'seg');
    labels.forEach(function (l, i) {
      var b = el('button', l, i === on ? 'on' : ''); b.type = 'button';
      b.addEventListener('click', function () { pick(i); });
      bar.appendChild(b);
    });
    return bar;
  }

  // Phase 2 level B: a shot the owner chose to share, already blurred to at most 48 px on the phone. Painted as
  // raw pixels on a canvas (no image decoder, no img-src), and refused if larger than the phone's ceiling.
  var SHOT_MAX = 48;
  function shotNode(s) {
    var w = +s.w, h = +s.h, raw;
    if (!(w >= 1 && h >= 1 && w <= SHOT_MAX && h <= SHOT_MAX)) return null;
    try { raw = atob(String(s.rgb || '')); } catch (e) { return null; }
    if (raw.length !== w * h * 3) return null;
    var c = el('canvas'); c.width = w; c.height = h;
    c.style.width = '200px'; c.style.height = Math.round(200 * h / w) + 'px'; c.style.borderRadius = '8px';
    var ctx = c.getContext('2d'), img = ctx.createImageData(w, h);
    for (var i = 0; i < w * h; i++) {
      img.data[i * 4] = raw.charCodeAt(i * 3); img.data[i * 4 + 1] = raw.charCodeAt(i * 3 + 1);
      img.data[i * 4 + 2] = raw.charCodeAt(i * 3 + 2); img.data[i * 4 + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
    var box = el('div', null, 'card');
    box.appendChild(c);
    box.appendChild(el('p', String(s.app || 'An app') + ' · flagged ' + String(s.label || '') + ' ' + (+s.pct || 0) + '% · ' +
      new Date(+s.shot_t || +s.t || 0).toLocaleString(), 'muted'));
    if (s.note) box.appendChild(el('p', '“' + String(s.note).slice(0, 200) + '”'));
    return box;
  }

  var state = { range: 1, tab: 0 };
  var RANGES = [['Today', 24 * 3600e3], ['7 days', 7 * 24 * 3600e3]];

  function render(allDigests, allShots) {
    var cut = Date.now() - RANGES[state.range][1];
    var digests = allDigests.filter(function (d) { return (+d.to || 0) >= cut; });
    var shots = allShots.filter(function (s) { return (+s.shot_t || +s.t || 0) >= cut; });
    var m = merge(digests);
    var NET = /^Filter problem: (degraded|upstream_down)$/;
    m.net += m.events.filter(function (e) { return NET.test(e.text); }).length;
    m.events = m.events.filter(function (e) { return !NET.test(e.text); });

    var out = [seg(RANGES.map(function (r) { return r[0]; }), state.range, function (i) { state.range = i; render(allDigests, allShots); })];
    if (!digests.length) { out.push(el('p', 'No report slices in this period.', 'muted')); return show(out); }

    // Summary first: the answer to "is it on, and did anything happen".
    out.push(m.off === 0 ? el('p', 'Protection was on the whole time.', 'ok') : el('p', 'Protection was OFF for ' + m.off + ' minutes.', 'bad'));
    if (m.missing > 0) out.push(el('p', m.missing + ' records are missing from the log (deleted or lost).', 'bad'));
    var stats = el('div', null, 'stats');
    [['Changes', m.events.length], ['Blocked', m.blocked], ['Lookups', m.lookups], ['Flagged screenshots', m.flagged]].forEach(function (s) {
      var c = el('div', null, 'stat'); c.appendChild(el('b', s[1])); c.appendChild(el('span', s[0], 'muted')); stats.appendChild(c);
    });
    out.push(stats);
    if (m.net > 0) out.push(el('p', 'Bad network ' + m.net + (m.net === 1 ? ' time' : ' times') + '. That is the phone\u2019s connection, not the filter being off.', 'muted'));
    if (m.from && m.to) out.push(el('p', 'Covers ' + new Date(m.from).toLocaleString() + ' to ' + new Date(m.to).toLocaleString() + ' (' + digests.length + ' slices).', 'muted'));

    var tabs = ['Changes (' + m.events.length + ')', 'Screenshots (' + shots.length + ')', 'Apps & sites', 'Blocked'];
    out.push(seg(tabs, state.tab, function (i) { state.tab = i; render(allDigests, allShots); }));
    var body = el('div', null, 'card');
    if (state.tab === 0) {
      if (!m.events.length) body.appendChild(el('p', 'No changes in this period.', 'muted'));
      var lastDay = '';
      m.events.slice().reverse().forEach(function (e) {
        var day = new Date(e.t).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });
        if (day !== lastDay) { body.appendChild(el('p', day, 'day')); lastDay = day; }
        body.appendChild(el('p', e.text));
      });
    } else if (state.tab === 1) {
      if (!shots.length) body.appendChild(el('p', 'No screenshots shared in this period.', 'muted'));
      else body.appendChild(el('p', 'The owner chose to send these. They are blurred on the phone on purpose; the clear image never leaves it.', 'muted'));
      shots.sort(function (a, b) { return (+b.t || 0) - (+a.t || 0); }).slice(0, 50).forEach(function (s) { var nd = shotNode(s); if (nd) body.appendChild(nd); });
    } else if (state.tab === 2) {
      body.appendChild(el('h2', 'App time')); body.appendChild(sortable('App', m.lists.apps_min, 'Minutes'));
      body.appendChild(el('h2', 'Browser sites')); body.appendChild(sortable('Site', m.lists.sites, 'Visits'));
    } else {
      body.appendChild(el('h2', 'By category')); body.appendChild(sortable('Category', m.lists.cats, 'Blocked'));
      body.appendChild(el('h2', 'Category sites')); body.appendChild(sortable('Site', m.lists.cat_hosts, 'Blocked'));
      body.appendChild(el('h2', 'Most blocked')); body.appendChild(sortable('Address', m.lists.top_blocked, 'Blocked'));
    }
    out.push(body);
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
          el('p', 'Open the Focus Fence link the phone\'s owner sent you (or a "key link" from them). It carries the key and ' +
            'opens this report in whichever browser it lands in.', 'muted')]);
        return;
      }
      var shots = digests.filter(function (d) { return d.kind === 'shot'; });
      render(digests.filter(function (d) { return d.kind !== 'shot'; }), shots);
    })
    .catch(function () { show([el('p', 'Could not load the report. Try again later.', 'bad')]); });
})();

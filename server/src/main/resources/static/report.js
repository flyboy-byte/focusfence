// Focus Fence partner pages (D-023, plan 13). The phone seals each report slice with a report key the server never
// sees. This browser joins as one of the partner's devices: it makes its own P-256 key pair (the private half can't
// leave this browser, IndexedDB holds it), sends only the public half, and the owner's phone seals the report key to
// it. Here we open that sealed key, then the slices (WebCrypto AES-GCM), and merge them into one 7-day report. All
// output is built with textContent, never innerHTML: the report text comes from the phone, so it is treated as data.
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

  // ---- this browser as a partner device (plan 13) ----
  // One record per report link: { deviceId, priv }, priv a non-extractable ECDH CryptoKey.
  function idb() {
    return new Promise(function (res, rej) {
      var r = indexedDB.open('ff_devices', 1);
      r.onupgradeneeded = function () { r.result.createObjectStore('d'); };
      r.onsuccess = function () { res(r.result); };
      r.onerror = function () { rej(r.error); };
    });
  }
  function idbDo(mode, fn) {
    return idb().then(function (db) {
      return new Promise(function (res, rej) {
        var tx = db.transaction('d', mode), req = fn(tx.objectStore('d'));
        tx.oncomplete = function () { res(req && req.result); };
        tx.onerror = function () { rej(tx.error); };
      });
    });
  }
  function devGet(token) { return idbDo('readonly', function (st) { return st.get(token); }).catch(function () { return null; }); }
  function devPut(token, rec) { return idbDo('readwrite', function (st) { return st.put(rec, token); }); }
  function devDel(token) { return idbDo('readwrite', function (st) { return st.delete(token); }).catch(function () {}); }
  function b64(buf) { var b = new Uint8Array(buf), s = ''; for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return btoa(s); }

  async function newDeviceKey() {
    var kp = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    return { priv: kp.privateKey, spki: b64(await crypto.subtle.exportKey('spki', kp.publicKey)) };
  }
  // dns/Ecies with the "focusfence-key-v1" label: "FFS1" | u16 len | ephemeral SPKI | iv[12] | AES-GCM ciphertext.
  async function unseal(priv, sealed) {
    var raw = b64u(sealed);
    if (raw.length < 6 || String.fromCharCode(raw[0], raw[1], raw[2], raw[3]) !== 'FFS1') throw new Error('not sealed');
    var n = (raw[4] << 8) | raw[5], eph = raw.slice(6, 6 + n), iv = raw.slice(6 + n, 18 + n), ct = raw.slice(18 + n);
    var pub = await crypto.subtle.importKey('spki', eph, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    var shared = await crypto.subtle.deriveBits({ name: 'ECDH', public: pub }, priv, 256);
    var hk = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveBits']);
    var k = await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(32), info: new TextEncoder().encode('focusfence-key-v1') }, hk, 256);
    var aes = await crypto.subtle.importKey('raw', k, 'AES-GCM', false, ['decrypt']);
    return new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv }, aes, ct));
  }
  function browserLabel() {
    var u = navigator.userAgent, b = /Edg\//.test(u) ? 'Edge' : /Firefox\//.test(u) ? 'Firefox' : /Chrome\//.test(u) ? 'Chrome' : /Safari\//.test(u) ? 'Safari' : 'Browser';
    var os = /Android/.test(u) ? 'Android' : /iPhone|iPad/.test(u) ? 'iPhone' : /Windows/.test(u) ? 'Windows' : /Mac OS/.test(u) ? 'Mac' : /Linux/.test(u) ? 'Linux' : '';
    return os ? b + ' on ' + os : b;
  }
  async function errorText(r) { try { var j = await r.json(); if (j && j.error) return String(j.error); } catch (e) {} return 'Something went wrong (' + r.status + '). Try again.'; }

  // Plan 12 §5: quiet hours and the daily message use the partner's own time zone, which only the browser knows.
  var tzIn = document.getElementById('tz');
  if (tzIn) {
    try {
      var zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      if (zone) { tzIn.value = zone; var tzName = document.getElementById('tzname'); if (tzName) tzName.textContent = zone; }
    } catch (e) { /* the stored zone stays */ }
  }

  var page = document.body.getAttribute('data-page');
  if (page === 'join') {
    var j = document.getElementById('join');
    if (!j) return;
    var link = j.getAttribute('data-link') || '', code = j.getAttribute('data-code') || '';
    var msg = document.getElementById('jmsg');
    // On Android, offer the app: the invite goes to it in an intent (focusfence://join), and it joins as an app device.
    var oa = document.getElementById('openapp');
    if (oa && /Android/i.test(navigator.userAgent)) {
      var pkg = (j.getAttribute('data-package') || '').replace(/[^A-Za-z0-9_.]/g, '');
      var q = 'server=' + encodeURIComponent(location.origin) + (link ? '&link=' + encodeURIComponent(link) : '&code=' + encodeURIComponent(code));
      var a = el('a', 'Open in the Focus Fence app');
      a.href = 'intent://join?' + q + '#Intent;scheme=focusfence;package=' + pkg +
        ';S.browser_fallback_url=' + encodeURIComponent('https://github.com/flyboy-byte/focusfence/releases/latest') + ';end';
      var b = el('b'); b.appendChild(a); oa.appendChild(b);
      oa.appendChild(el('span', ' Have the app? Join there, and alerts arrive as notifications.', 'muted'));
    }
    var go = document.getElementById('jgo');
    if (!window.crypto || !crypto.subtle || !window.indexedDB) { go.disabled = true; msg.textContent = 'This browser can\u2019t make the key. Open the link in Chrome, Firefox or Safari.'; return; }
    go.addEventListener('click', async function () {
      go.disabled = true; msg.textContent = 'Setting up this browser\u2026';
      try {
        var k = await newDeviceKey();
        var r = await fetch('/v1/join', { method: 'POST', credentials: 'omit', headers: { 'Content-Type': 'application/json', 'X-FF-Protocol': '2' },
          body: JSON.stringify({ link: link, code: code, name: (document.getElementById('jname') || {}).value || '', kind: 'web', label: browserLabel(), publicKeySpki: k.spki }) });
        if (!r.ok) { msg.textContent = await errorText(r); go.disabled = false; return; }
        var res = await r.json();
        await devPut(res.token, { deviceId: res.deviceId, priv: k.priv });
        location.href = '/p/' + res.token;
      } catch (e) { msg.textContent = 'Something went wrong. Try again.'; go.disabled = false; }
    });
    return;
  }
  if (page !== 'report') return;

  // The check-in time is written by the server in its own time zone; say it in the reader's terms instead.
  var seen = document.getElementById('seen');
  if (seen && +seen.getAttribute('data-t') > 0) {
    var st = +seen.getAttribute('data-t');
    seen.textContent = (seen.getAttribute('data-online') === '1' ? 'Last check-in ' : 'Silent since ') + ago(st) + ' (' + hm(st) + ')';
  }
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
    var m = { off: 0, offDays: {}, missing: 0, net: 0, lookups: 0, blocked: 0, enforce: 0, flagged: 0, events: [],
      lists: { cats: {}, cat_hosts: {}, apps_min: {}, sites: {}, top_blocked: {} }, from: null, to: null };
    digests.sort(function (a, b) { return (a.from || 0) - (b.from || 0); }).forEach(function (d) {
      m.off += +d.off_min || 0; m.missing += +d.missing || 0;
      // Which days it was off, so "off for 130 minutes" says when: a week-old outage isn't news from today.
      if (+d.off_min > 0) {
        var day = new Date(+d.to || 0).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
        m.offDays[day] = (m.offDays[day] || 0) + (+d.off_min);
      } m.net += +d.net_trouble || 0; m.lookups += +d.lookups || 0;
      m.blocked += +d.blocked || 0; m.enforce += +d.enforce || 0; m.flagged += +d.flagged_shots || 0;
      // A change has no time of its own: it happened within its slice, so it is shown under that slice's day.
      String(d.events || '').split('\n').forEach(function (e) { if (e.trim()) m.events.push({ t: +d.to || 0, from: +d.from || 0, text: e }); });
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
    var max = rows.reduce(function (a, r) { return Math.max(a, r[1]); }, 1);
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
        .slice(0, 100).forEach(function (r) {
          var tr = el('tr'), name = el('td'), bar = el('span', null, 'bar');
          name.appendChild(el('span', r[0]));
          // The row's share of the biggest value, so the list reads at a glance without comparing numbers.
          bar.style.width = Math.max(2, Math.round(100 * r[1] / max)) + '%';
          name.appendChild(bar);
          tr.appendChild(name); tr.appendChild(el('td', r[1], 'n')); t.appendChild(tr);
        });
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
    if (s.auto) box.appendChild(el('p', 'Sent automatically', 'tag'));
    box.appendChild(el('p', String(s.app || 'An app') + ' · flagged ' + String(s.label || '') + ' ' + (+s.pct || 0) + '% · ' +
      new Date(+s.shot_t || +s.t || 0).toLocaleString(), 'muted'));
    if (s.note) box.appendChild(el('p', '“' + String(s.note).slice(0, 200) + '”'));
    return box;
  }

  function ago(t) {
    var m = Math.round((Date.now() - t) / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return m + ' min ago';
    if (m < 48 * 60) return Math.round(m / 60) + ' h ago';
    return Math.round(m / 1440) + ' days ago';
  }
  function hm(t) { return new Date(t).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }); }
  function dayName(t) { return new Date(t).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' }); }
  // Server alerts that are bad news; "checking in again" is good news and shown plainly.
  function alarming(text) { return !/checking in again|turned on\b|→ true$/.test(text); }
  // The first sentence is enough in a summary; the timeline has the full text.
  function short(text) { var i = text.indexOf('. '); return i > 0 ? text.slice(0, i + 1) : text; }

  // One bar per day: blocked lookups, with protection-off minutes and changes called out under it.
  function dayBars(digests) {
    var days = {}, order = [];
    digests.forEach(function (d) {
      var key = new Date(+d.to || 0).toDateString();
      if (!days[key]) { days[key] = { t: +d.to || 0, blocked: 0, off: 0, changes: 0, flagged: 0 }; order.push(key); }
      var x = days[key];
      x.blocked += +d.blocked || 0; x.off += +d.off_min || 0; x.flagged += +d.flagged_shots || 0;
      x.changes += String(d.events || '').split('\n').filter(function (e) { return e.trim(); }).length;
    });
    var max = order.reduce(function (a, k) { return Math.max(a, days[k].blocked); }, 1);
    var wrap = el('div', null, 'days');
    order.sort(function (a, b) { return days[a].t - days[b].t; }).forEach(function (k) {
      var x = days[k], row = el('div', null, 'dayrow');
      row.appendChild(el('span', new Date(x.t).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' }), 'dl'));
      var track = el('span', null, 'track'), fill = el('span', null, 'fill');
      fill.style.width = Math.max(1, Math.round(100 * x.blocked / max)) + '%';
      track.appendChild(fill); row.appendChild(track);
      var notes = [x.blocked + ' blocked'];
      if (x.off) notes.push(x.off + ' min off');
      if (x.flagged) notes.push(x.flagged + ' flagged');
      row.appendChild(el('span', notes.join(' · '), x.off ? 'dn bad' : 'dn muted'));
      wrap.appendChild(row);
    });
    return wrap;
  }

  // Alerts (exact times, from the server) and setting changes (from the report, by slice) in one list, newest first.
  function timeline(alerts, events) {
    var items = alerts.map(function (a) { return { t: a.t, text: a.text, kind: alarming(a.text) ? 'alert' : 'good', exact: true }; })
      .concat(events.map(function (e) { return { t: e.t, text: e.text, kind: 'change', exact: false }; }))
      .sort(function (a, b) { return b.t - a.t; });
    var body = el('div');
    if (!items.length) body.appendChild(el('p', 'Nothing happened in this period.', 'muted'));
    var lastDay = '';
    items.forEach(function (it) {
      var day = dayName(it.t);
      if (day !== lastDay) { body.appendChild(el('p', day, 'day')); lastDay = day; }
      var row = el('div', null, 'ev ' + it.kind);
      row.appendChild(el('span', it.exact ? hm(it.t) : 'by ' + hm(it.t), 'evt'));
      row.appendChild(el('span', it.text));
      body.appendChild(row);
    });
    return body;
  }

  var state = { range: 1, tab: 0 };
  var RANGES = [['Today', 24 * 3600e3], ['7 days', 7 * 24 * 3600e3]];

  function render(allDigests, allShots, allAlerts) {
    var cut = Date.now() - RANGES[state.range][1];
    var alerts = allAlerts.filter(function (a) { return a.t >= cut; });
    var digests = allDigests.filter(function (d) { return (+d.to || 0) >= cut; });
    var shots = allShots.filter(function (s) { return (+s.shot_t || +s.t || 0) >= cut; });
    var m = merge(digests);
    var NET = /^Filter problem: (degraded|upstream_down)$/;
    m.net += m.events.filter(function (e) { return NET.test(e.text); }).length;
    m.events = m.events.filter(function (e) { return !NET.test(e.text); });
    // A protection change is both a server alert (exact time) and a line in its report slice: show it once, as the alert.
    m.events = m.events.filter(function (e) {
      return !alerts.some(function (a) { return a.text === e.text && a.t >= e.from - 60e3 && a.t <= e.t + 60e3; });
    });

    var out = [seg(RANGES.map(function (r) { return r[0]; }), state.range, function (i) { state.range = i; render(allDigests, allShots, allAlerts); })];
    if (!digests.length) {
      out.push(el('p', 'No report data in this period.', 'muted'));
      if (alerts.length) out.push(timeline(alerts, []));
      return show(out);
    }

    // Summary first: the answer to "is it on, and did anything happen".
    var attn = el('div', null, 'card attn');
    attn.appendChild(el('h2', 'Needs attention'));
    var items = [];
    if (m.off > 0) {
      var when = Object.keys(m.offDays).map(function (k) { return k + ' (' + m.offDays[k] + ' min)'; }).join(', ');
      items.push(['Protection was off for ' + m.off + ' minutes in this period: ' + when + '.', 'bad']);
    }
    if (m.missing > 0) items.push([m.missing + ' records are missing from the log (deleted or lost).', 'bad']);
    alerts.filter(function (a) { return alarming(a.text); }).slice(-5).reverse().forEach(function (a) {
      items.push([dayName(a.t).split(',')[0] + ' ' + hm(a.t) + ': ' + short(a.text), 'bad']);
    });
    if (m.flagged > 0) items.push([m.flagged + ' flagged screenshot' + (m.flagged === 1 ? '' : 's') + '. See Screenshots.', '']);
    if (!items.length) attn.appendChild(el('p', 'Nothing. Protection was on the whole time.', 'ok'));
    items.forEach(function (x) { attn.appendChild(el('p', x[0], x[1])); });
    out.push(attn);
    var stats = el('div', null, 'stats');
    [['Alerts', alerts.filter(function (a) { return alarming(a.text); }).length], ['Changes', m.events.length], ['Blocked', m.blocked], ['Flagged', m.flagged]].forEach(function (s) {
      var c = el('div', null, 'stat'); c.appendChild(el('b', s[1])); c.appendChild(el('span', s[0], 'muted')); stats.appendChild(c);
    });
    out.push(stats);
    if (m.net > 0) out.push(el('p', 'Bad network ' + m.net + (m.net === 1 ? ' time' : ' times') + '. That is the phone\u2019s connection, not the filter being off.', 'muted'));
    if (m.to) out.push(el('p', 'Latest data ' + ago(m.to) + ' (' + hm(m.to) + '). New data arrives about every 15 minutes; alerts come at once.', 'muted'));
    if (state.range === 1 && digests.length) { out.push(el('h2', 'Day by day')); out.push(dayBars(digests)); }

    var tabs = ['Timeline (' + (m.events.length + alerts.length) + ')', 'Screenshots (' + shots.length + ')', 'Apps & sites', 'Blocked'];
    out.push(seg(tabs, state.tab, function (i) { state.tab = i; render(allDigests, allShots, allAlerts); }));
    var body = el('div', null, 'card');
    if (state.tab === 0) {
      body.appendChild(timeline(alerts, m.events));
    } else if (state.tab === 1) {
      if (!shots.length) body.appendChild(el('p', 'No screenshots shared in this period.', 'muted'));
      else body.appendChild(el('p', 'Flagged screenshots go to you automatically about 10 minutes after the AI flags them; the owner can add a note but can\u2019t stop them. They are blurred on the phone on purpose: the clear image never leaves it. The AI is often wrong, so read the note before assuming the worst.', 'muted'));
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

  var base = location.pathname.replace(/\/$/, '');
  var token = base.split('/').pop();
  // The Focus Fence app opens this page with its own copy of the key after '#' (never sent to a server), and app=1:
  // it isn't a browser device, so it is never offered "Add this browser".
  var hashParams = new URLSearchParams(location.hash.slice(1));
  var appMode = hashParams.get('app') === '1';
  if (hashParams.get('k')) { saveKey(hashParams.get('k')); history.replaceState(null, '', location.pathname + location.search); }
  var owner = box.getAttribute('data-owner') || 'They';

  // This browser's copy of the report key: 'ok' (saved), 'waiting' (the owner's phone hasn't sent it yet), 'none'.
  async function deviceKey() {
    var rec = await devGet(token);
    if (!rec || !rec.deviceId) return 'none';
    var r = await fetch(base + '/key?d=' + encodeURIComponent(rec.deviceId), { cache: 'no-store', credentials: 'omit' });
    if (r.status === 404) { await devDel(token); return 'none'; }
    if (!r.ok) return 'waiting';
    var k = await r.json();
    if (!k.ready) return 'waiting';
    try { saveKey(await unseal(rec.priv, k.sealed)); return 'ok'; } catch (e) { return 'none'; }
  }
  var waitedSince = Date.now(), wasWaiting = false;
  function waiting() {
    wasWaiting = true;
    show([el('p', 'Waiting for ' + owner + '\u2019s phone\u2026', ''),
      el('p', 'You\u2019re in. ' + owner + '\u2019s phone sends this browser the key to the report the next time it checks in, usually within a minute. This page opens the report by itself.', 'muted')]);
    // Every 20 s for 30 minutes; after that the phone is probably off, and a reload tries again.
    if (Date.now() - waitedSince < 30 * 60e3) setTimeout(load, 20e3);
  }
  function notADevice() {
    var add = el('button', 'Add this browser'); add.type = 'button';
    var note = el('p', '', 'muted');
    add.addEventListener('click', async function () {
      add.disabled = true; note.textContent = 'Setting up this browser\u2026';
      try {
        var k = await newDeviceKey();
        var r = await fetch(base + '/devices', { method: 'POST', credentials: 'omit', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ kind: 'web', label: browserLabel(), publicKeySpki: k.spki }) });
        if (!r.ok) { note.textContent = await errorText(r); add.disabled = false; return; }
        await devPut(token, { deviceId: (await r.json()).deviceId, priv: k.priv });
        waitedSince = Date.now(); load();
      } catch (e) { note.textContent = 'Something went wrong. Try again.'; add.disabled = false; }
    });
    show([el('p', 'This browser isn\u2019t one of your devices yet, so it can\u2019t open the report.', 'bad'),
      el('p', 'Add it, and ' + owner + '\u2019s phone sends it the key at its next check-in. ' + owner + ' and you are both told a device was added.', 'muted'), add, note]);
  }

  function load() {
    // Alerts are the plain lines partners are emailed (no key needed); a failure here must not hide the report.
    var alertsReq = fetch(base + '/alerts', { cache: 'no-store', credentials: 'omit' })
      .then(function (r) { return r.ok ? r.json() : { alerts: [] }; })
      .then(function (a) { return (a.alerts || []).filter(function (x) { return x && typeof x.text === 'string' && +x.t > 0; }); })
      .catch(function () { return []; });
    if (!window.crypto || !crypto.subtle || !window.indexedDB) {
      show([el('p', 'This browser cannot open encrypted reports. Open the link in Chrome, Firefox or Safari.', 'bad')]);
      return;
    }
    // The device key first, every load: after the owner starts a new key, the old one opens only older slices.
    deviceKey().catch(function () { return 'waiting'; }).then(function (dk) {
      // The key just arrived: reload once, so the server-drawn parts (the devices list) say so too.
      if (dk === 'ok' && wasWaiting) { location.reload(); return; }
      return fetch(base + '/data', { cache: 'no-store', credentials: 'omit' })
        .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then(async function (data) {
          var serverAlerts = await alertsReq;
          var keys = loadKeys();
          if (!keys.length) return dk === 'none' && !appMode ? notADevice() : waiting();
          var reports = data.reports || [];
          if (!reports.length) {
            show([el('p', 'No report yet. The phone sends one every hour.', 'muted')].concat(serverAlerts.length ? [timeline(serverAlerts, [])] : []));
            return;
          }
          var digests = await open(keys, data.deviceId, reports);
          if (!digests.length) return dk === 'none' && !appMode ? notADevice() : waiting();
          var shots = digests.filter(function (d) { return d.kind === 'shot'; });
          render(digests.filter(function (d) { return d.kind !== 'shot'; }), shots, serverAlerts);
        });
    }).catch(function () { show([el('p', 'Could not load the report. Try again later.', 'bad')]); });
  }
  load();
})();

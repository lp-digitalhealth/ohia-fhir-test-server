// Received-payloads viewer.
//
// SAFETY RULES (a test in the repository enforces the first three):
//  1. Everything a sender controls is inserted with textContent / text nodes. Never innerHTML.
//  2. No eval, no Function constructor, no document.write.
//  3. No inline handlers or inline styles; the page's CSP forbids them.
//  4. Images are fetched with the token and shown from blob: URLs, and only if the server says they
//     are an image (the server decides that from the bytes, not from anything the sender declared).
(function () {
  'use strict';

  var TOKEN_KEY = 'ohiaViewerToken';
  var token = '';
  var blobUrls = [];

  function $(id) { return document.getElementById(id); }

  function el(tag, text, cls) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }

  function clear(node) {
    while (node.firstChild) { node.removeChild(node.firstChild); }
  }

  function say(text, isError) {
    var m = $('message');
    m.textContent = text || '';
    m.className = isError ? 'message error' : 'message';
  }

  function readStoredToken() {
    try { return window.sessionStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }

  function storeToken(value) {
    try {
      if (value) { window.sessionStorage.setItem(TOKEN_KEY, value); } else { window.sessionStorage.removeItem(TOKEN_KEY); }
    } catch (e) { /* storage may be blocked; the token then lives only in memory */ }
  }

  function api(path) {
    return fetch(path, { headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'application/json' }, cache: 'no-store' })
      .then(function (r) {
        if (r.status === 401) { throw new Error('The access token was not accepted.'); }
        if (!r.ok) { throw new Error('The server answered ' + r.status + '.'); }
        return r;
      });
  }

  function badge(text, kind) { return el('span', text, 'badge ' + kind); }

  function statusBadge(status) {
    if (status === 'saved') { return badge('saved, digest verified', 'ok'); }
    if (status === 'not-pulled') { return badge('not pulled', 'warn'); }
    return badge(status, 'bad');
  }

  function shortTime(iso) {
    var d = new Date(iso);
    return isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
  }

  // ---------------------------------------------------------------- list

  function renderList(receipts) {
    var host = $('list');
    clear(host);
    if (!receipts.length) {
      host.appendChild(el('p', 'No payloads received yet.', 'empty'));
      return;
    }
    var table = el('table');
    var head = el('tr');
    ['Received', 'Referral', 'Manifest', 'Images', 'Findings', ''].forEach(function (h) { head.appendChild(el('th', h)); });
    table.appendChild(head);
    receipts.forEach(function (r) {
      var saved = r.objects.filter(function (o) { return o.status === 'saved'; }).length;
      var row = el('tr');
      row.appendChild(el('td', shortTime(r.receivedAt)));
      row.appendChild(el('td', r.referralId || '(none)', 'mono'));
      row.appendChild(el('td', r.manifestId || '(none)', 'mono'));
      row.appendChild(el('td', saved + ' of ' + r.objects.length + ' saved'));
      row.appendChild(el('td', r.findings.length));
      var cell = el('td');
      var open = el('button', 'Open', 'link');
      open.type = 'button';
      open.addEventListener('click', function () { showDetail(r.id); });
      cell.appendChild(open);
      row.appendChild(cell);
      table.appendChild(row);
    });
    host.appendChild(table);
  }

  function loadList() {
    if (!token) { say('Enter the access token first.', true); return Promise.resolve(); }
    say('Loading...');
    return api('/ode/receipts?limit=50').then(function (r) { return r.json(); }).then(function (data) {
      renderList(data);
      say('Loaded ' + data.length + ' receipt(s).');
    }).catch(function (e) { say(e.message, true); });
  }

  // ---------------------------------------------------------------- detail

  function fact(dl, name, value) {
    dl.appendChild(el('dt', name));
    dl.appendChild(el('dd', value === null || value === undefined || value === '' ? '(none)' : value));
  }

  function releaseBlobs() {
    blobUrls.forEach(function (u) { URL.revokeObjectURL(u); });
    blobUrls = [];
  }

  function showDetail(id) {
    say('Loading receipt...');
    api('/ode/receipts/' + encodeURIComponent(id)).then(function (r) { return r.json(); }).then(function (r) {
      renderDetail(r);
      say('');
    }).catch(function (e) { say(e.message, true); });
  }

  function renderDetail(r) {
    releaseBlobs();
    $('detail-card').hidden = false;
    var host = $('detail');
    clear(host);

    var dl = el('dl', null, 'facts');
    fact(dl, 'Received', shortTime(r.receivedAt));
    fact(dl, 'Referral id', r.referralId);
    fact(dl, 'Manifest id', r.manifestId);
    fact(dl, 'Payload stored', r.accepted ? 'yes, exactly as received (' + r.hapiEntries + ' entries)' : 'no');
    fact(dl, 'Sender (ARex base)', r.sender);
    fact(dl, 'Token endpoint', r.tokenEndpoint);
    fact(dl, 'authorization_details type', r.authorizationDetailsType);
    fact(dl, 'Pull stopped because', r.stoppedBecause);
    host.appendChild(dl);

    host.appendChild(el('h3', 'Findings'));
    var ul = el('ul', null, 'findings');
    if (!r.findings.length) {
      ul.appendChild(el('li', 'No findings.', 'none'));
    }
    r.findings.forEach(function (f) {
      var li = el('li');
      li.appendChild(el('span', f.code, 'finding-code'));
      if (f.specRef) { li.appendChild(el('span', '  (' + f.specRef + ')', 'finding-ref')); }
      li.appendChild(el('div', f.message));
      ul.appendChild(li);
    });
    host.appendChild(ul);

    host.appendChild(el('h3', 'Images'));
    if (!r.objects.length) {
      host.appendChild(el('p', 'No content objects.', 'empty'));
    } else {
      var wrap = el('div', null, 'scroll');
      var table = el('table');
      var head = el('tr');
      ['Entry', 'Result', 'Bytes', 'SHA-256 (computed)'].forEach(function (h) { head.appendChild(el('th', h)); });
      table.appendChild(head);
      r.objects.forEach(function (o) {
        var row = el('tr');
        row.appendChild(el('td', o.entryId, 'mono'));
        var s = el('td');
        s.appendChild(statusBadge(o.status));
        row.appendChild(s);
        row.appendChild(el('td', o.bytes));
        row.appendChild(el('td', o.sha256 || '', 'mono'));
        table.appendChild(row);
      });
      wrap.appendChild(table);
      host.appendChild(wrap);

      var thumbs = el('div', null, 'thumbs');
      host.appendChild(thumbs);
      r.objects.forEach(function (o) {
        if (o.viewable && o.fileKey) { addThumb(thumbs, r.id, o); }
      });
    }

    host.appendChild(el('h3', 'Steps'));
    var steps = el('ul', null, 'findings');
    r.steps.forEach(function (s) {
      var li = el('li', null);
      li.appendChild(badge(s.ok ? 'ok' : 'stopped', s.ok ? 'ok' : 'bad'));
      li.appendChild(el('span', '  ' + s.name + ': ' + s.detail));
      steps.appendChild(li);
    });
    if (!r.steps.length) { steps.appendChild(el('li', 'No pull was attempted.', 'none')); }
    host.appendChild(steps);
  }

  function addThumb(host, receiptId, o) {
    var fig = el('figure');
    var caption = el('figcaption', o.entryId + ' (' + o.sniffedType + ', ' + o.bytes + ' bytes)');
    fig.appendChild(caption);
    host.appendChild(fig);
    fetch('/ode/receipts/' + encodeURIComponent(receiptId) + '/images/' + encodeURIComponent(o.fileKey),
      { headers: { 'Authorization': 'Bearer ' + token }, cache: 'no-store' })
      .then(function (r) {
        var type = r.headers.get('Content-Type') || '';
        if (!r.ok || (type !== 'image/jpeg' && type !== 'image/png')) { throw new Error('not shown: the server did not mark it as an image'); }
        return r.blob();
      })
      .then(function (blob) {
        var url = URL.createObjectURL(blob);
        blobUrls.push(url);
        var img = document.createElement('img');
        img.alt = 'Image ' + o.entryId;
        img.src = url;
        fig.insertBefore(img, caption);
      })
      .catch(function (e) { caption.textContent = o.entryId + ': ' + e.message; });
  }

  // ---------------------------------------------------------------- wiring

  $('auth-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    token = $('token').value.trim();
    storeToken(token);
    $('detail-card').hidden = true;
    loadList();
  });

  $('forget').addEventListener('click', function () {
    token = '';
    storeToken('');
    $('token').value = '';
    releaseBlobs();
    clear($('list'));
    clear($('detail'));
    $('detail-card').hidden = true;
    say('Token forgotten.');
  });

  $('refresh').addEventListener('click', loadList);

  token = readStoredToken();
  if (token) { loadList(); }
}());

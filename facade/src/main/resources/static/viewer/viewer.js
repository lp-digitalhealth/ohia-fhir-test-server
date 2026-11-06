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
  var blobGroups = { received: [], send: [], sent: [] };

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

  function failure(r) {
    if (r.status === 401) { return Promise.reject(new Error('The access token was not accepted.')); }
    return r.json().catch(function () { return {}; }).then(function (b) {
      throw new Error(b && b.error ? b.error : 'The server answered ' + r.status + '.');
    });
  }

  function api(path) {
    return fetch(path, { headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'application/json' }, cache: 'no-store' })
      .then(function (r) { return r.ok ? r : failure(r); });
  }

  function apiPost(path, body) {
    var headers = { 'Authorization': 'Bearer ' + token, 'Accept': 'application/json' };
    var init = { method: 'POST', headers: headers, cache: 'no-store' };
    if (body !== undefined) { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
    return fetch(path, init).then(function (r) { return r.ok ? r : failure(r); });
  }

  function useCaseLabel(id) { return id === 'uc07' ? 'UC07' : 'UC04a'; }

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
      host.appendChild(el('p', 'Nothing received yet.', 'empty'));
      return;
    }
    var table = el('table');
    var head = el('tr');
    ['Received', 'Use case', 'Referral', 'Manifest', 'Images', 'Findings', ''].forEach(function (h) { head.appendChild(el('th', h)); });
    table.appendChild(head);
    receipts.forEach(function (r) {
      var saved = r.objects.filter(function (o) { return o.status === 'saved'; }).length;
      var row = el('tr');
      row.appendChild(el('td', shortTime(r.receivedAt)));
      row.appendChild(el('td', useCaseLabel(r.useCase)));
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
    var only = $('filter-usecase').value;
    return api('/ode/receipts?limit=50' + (only ? '&useCase=' + encodeURIComponent(only) : '')).then(function (r) { return r.json(); }).then(function (data) {
      renderList(data);
      say('Loaded ' + data.length + ' receipt(s).');
    }).catch(function (e) { say(e.message, true); });
  }

  // ---------------------------------------------------------------- detail

  function fact(dl, name, value) {
    dl.appendChild(el('dt', name));
    dl.appendChild(el('dd', value === null || value === undefined || value === '' ? '(none)' : value));
  }

  function releaseBlobs(group) {
    blobGroups[group].forEach(function (u) { URL.revokeObjectURL(u); });
    blobGroups[group] = [];
  }

  function showDetail(id) {
    say('Loading receipt...');
    api('/ode/receipts/' + encodeURIComponent(id)).then(function (r) { return r.json(); }).then(function (r) {
      renderDetail(r);
      say('');
    }).catch(function (e) { say(e.message, true); });
  }

  function renderDetail(r) {
    releaseBlobs('received');
    $('detail-card').hidden = false;
    var host = $('detail');
    clear(host);

    var dl = el('dl', null, 'facts');
    fact(dl, 'Received', shortTime(r.receivedAt));
    fact(dl, 'Use case', useCaseLabel(r.useCase));
    fact(dl, 'Referral id', r.referralId);
    fact(dl, 'Manifest id', r.manifestId);
    var dropped = r.findings.some(function (f) { return f.code === 'SUBSCRIPTION_NOT_STORED'; });
    fact(dl, 'Payload stored', !r.accepted ? 'no' : (dropped
      ? 'yes, but without its Subscription (' + r.hapiEntries + ' entries; see the findings)'
      : 'yes, exactly as received (' + r.hapiEntries + ' entries)'));
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
      var manifests = {};
      r.objects.forEach(function (o) { if (o.manifestId) { manifests[o.manifestId] = true; } });
      var several = Object.keys(manifests).length > 1;
      (several ? ['Entry', 'Manifest', 'Result', 'Bytes', 'SHA-256 (computed)'] : ['Entry', 'Result', 'Bytes', 'SHA-256 (computed)']).forEach(function (h) { head.appendChild(el('th', h)); });
      table.appendChild(head);
      r.objects.forEach(function (o) {
        var row = el('tr');
        row.appendChild(el('td', o.entryId, 'mono'));
        if (several) { row.appendChild(el('td', o.manifestId || '', 'mono')); }
        var s = el('td');
        s.appendChild(statusBadge(o.status));
        row.appendChild(s);
        row.appendChild(el('td', o.bytes));
        row.appendChild(el('td', o.sha256 || '', 'mono'));
        table.appendChild(row);
      });
      wrap.appendChild(table);
      host.appendChild(wrap);

      var files = r.objects.filter(function (o) { return o.status === 'saved' && !o.viewable && o.fileKey; });
      if (files.length) {
        host.appendChild(el('h3', 'Other files'));
        host.appendChild(el('p', 'These are not images, so they are not shown here. Download one to open it on your own computer.', 'note'));
        var list = el('p', null, 'row');
        files.forEach(function (o) { list.appendChild(downloadButton(r.id, o)); });
        host.appendChild(list);
      }
      var thumbs = el('div', null, 'thumbs');
      host.appendChild(thumbs);
      addThumbs(thumbs, 'received', r.objects.filter(function (o) { return o.viewable && o.fileKey; }).map(function (o) {
        return {
          path: '/ode/receipts/' + encodeURIComponent(r.id) + '/images/' + encodeURIComponent(o.fileKey),
          label: o.entryId,
          meta: (o.sniffedType || '') + ', ' + o.bytes + ' bytes, sha-256 ' + (o.sha256 || '')
        };
      }));
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

  // ---------------------------------------------------------------- downloads

  // The server hands non-image files over as a forced download (application/octet-stream), so they are never opened
  // as a page here. The file name is built from OUR safe key and the declared type, never from sender text.
  function extensionFor(type) {
    if (type === 'application/pdf') { return '.pdf'; }
    if (type === 'text/plain') { return '.txt'; }
    return '.bin';
  }

  function downloadButton(receiptId, o) {
    var button = el('button', 'Download ' + o.entryId + ' (' + o.bytes + ' bytes)', 'secondary');
    button.type = 'button';
    button.addEventListener('click', function () {
      fetch('/ode/receipts/' + encodeURIComponent(receiptId) + '/images/' + encodeURIComponent(o.fileKey),
        { headers: { 'Authorization': 'Bearer ' + token }, cache: 'no-store' })
        .then(function (resp) { return resp.ok ? resp.blob() : Promise.reject(new Error('The server answered ' + resp.status + '.')); })
        .then(function (blob) {
          var url = URL.createObjectURL(blob);
          var link = document.createElement('a');
          link.href = url;
          link.download = o.fileKey + extensionFor(o.declaredContentType);
          document.body.appendChild(link);
          link.click();
          document.body.removeChild(link);
          setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
        })
        .catch(function (e) { say(e.message, true); });
    });
    return button;
  }

  // ---------------------------------------------------------------- images

  // An item is { path, label, meta, url }. The picture is fetched with the token and shown from a blob: URL, and only
  // if the server says it is a JPEG or PNG (the server decides that from the bytes). Anything else is not shown.
  function addThumbs(host, group, items) {
    items.forEach(function (item, index) { addThumb(host, group, items, index); });
  }

  function addThumb(host, group, items, index) {
    var item = items[index];
    var fig = el('figure');
    var caption = el('figcaption', item.label + (item.meta ? ' (' + item.meta.split(', sha-256')[0] + ')' : ''));
    fig.appendChild(caption);
    host.appendChild(fig);
    fetch(item.path, { headers: { 'Authorization': 'Bearer ' + token }, cache: 'no-store' })
      .then(function (r) {
        var type = r.headers.get('Content-Type') || '';
        if (!r.ok || (type !== 'image/jpeg' && type !== 'image/png')) { throw new Error('not shown: the server did not mark it as an image'); }
        return r.blob();
      })
      .then(function (blob) {
        item.url = URL.createObjectURL(blob);
        blobGroups[group].push(item.url);
        var button = el('button', null, 'thumb');
        button.type = 'button';
        button.setAttribute('aria-label', 'Enlarge ' + item.label);
        var img = document.createElement('img');
        img.alt = 'Image ' + item.label;
        img.src = item.url;
        button.appendChild(img);
        button.addEventListener('click', function () { openLightbox(items, index, button); });
        fig.insertBefore(button, caption);
      })
      .catch(function (e) { item.url = null; caption.textContent = item.label + ': ' + e.message; });
  }

  // ---------------------------------------------------------------- lightbox

  var lb = { items: [], index: 0, opener: null };

  function lightboxOpen() { return !$('lightbox').hidden; }

  function shownItems() { return lb.items.filter(function (i) { return i.url; }); }

  function renderLightbox() {
    var list = shownItems();
    var item = list[lb.index];
    if (!item) { closeLightbox(); return; }
    $('lightbox-title').textContent = item.label;
    $('lightbox-meta').textContent = item.meta || '';
    $('lightbox-img').src = item.url;
    $('lightbox-img').alt = 'Image ' + item.label;
    $('lightbox-count').textContent = (lb.index + 1) + ' of ' + list.length;
    $('lightbox-prev').disabled = list.length < 2;
    $('lightbox-next').disabled = list.length < 2;
  }

  function openLightbox(items, index, opener) {
    lb.items = items;
    lb.opener = opener || null;
    var clicked = items[index];
    lb.index = Math.max(0, shownItems().indexOf(clicked));
    $('lightbox-stage').className = 'lightbox-stage';
    $('lightbox-zoom').textContent = 'Actual size';
    $('lightbox').hidden = false;
    renderLightbox();
    $('lightbox-close').focus();
  }

  function closeLightbox() {
    $('lightbox').hidden = true;
    $('lightbox-img').removeAttribute('src');
    if (lb.opener && document.body.contains(lb.opener)) { lb.opener.focus(); }
    lb = { items: [], index: 0, opener: null };
  }

  function stepLightbox(by) {
    var n = shownItems().length;
    if (n < 2) { return; }
    lb.index = (lb.index + by + n) % n;
    renderLightbox();
  }

  function toggleZoom() {
    var stage = $('lightbox-stage');
    var actual = stage.className.indexOf('actual') >= 0;
    stage.className = actual ? 'lightbox-stage' : 'lightbox-stage actual';
    $('lightbox-zoom').textContent = actual ? 'Actual size' : 'Fit to screen';
  }

  // ---------------------------------------------------------------- tabs

  var VIEWS = ['received', 'send', 'sent'];

  function showView(name) {
    $('lightbox-close').addEventListener('click', closeLightbox);
  $('lightbox-prev').addEventListener('click', function () { stepLightbox(-1); });
  $('lightbox-next').addEventListener('click', function () { stepLightbox(1); });
  $('lightbox-zoom').addEventListener('click', toggleZoom);
  $('lightbox').addEventListener('click', function (ev) { if (ev.target === $('lightbox')) { closeLightbox(); } });
  document.addEventListener('keydown', function (ev) {
    if (!lightboxOpen()) { return; }
    if (ev.key === 'Escape') { closeLightbox(); }
    else if (ev.key === 'ArrowLeft') { stepLightbox(-1); }
    else if (ev.key === 'ArrowRight') { stepLightbox(1); }
  });
  VIEWS.forEach(function (v) {
      $('view-' + v).hidden = v !== name;
      $('tab-' + v).setAttribute('aria-pressed', v === name ? 'true' : 'false');
    });
    if (!token) { return; }
    if (name === 'send') { loadClients(); refreshSeedStatus(); }
    if (name === 'sent') { loadSent(); }
  }

  // ---------------------------------------------------------------- send

  var clients = [];
  var lastAutoTarget = '';

  function sendSay(text, isError) {
    var m = $('send-message');
    m.textContent = text || '';
    m.className = isError ? 'message error' : 'message';
  }

  function chosenUseCase() { return $('send-usecase').value; }

  function chosenClient() {
    var id = $('send-client').value;
    return clients.filter(function (c) { return c.clientId === id; })[0] || null;
  }

  function fillTargetFromClient() {
    var c = chosenClient();
    var box = $('send-target');
    var first = c && c.allowedTargetPrefixes.length ? c.allowedTargetPrefixes[0] : '';
    if (box.value === '' || box.value === lastAutoTarget) { box.value = first; lastAutoTarget = first; }
  }

  function loadClients() {
    return api('/ode/clients').then(function (r) { return r.json(); }).then(function (list) {
      clients = list;
      var select = $('send-client');
      var keep = select.value;
      clear(select);
      if (!list.length) { select.appendChild(el('option', 'No testers are registered yet')); select.disabled = true; sendSay('The organizer must register a tester first.', true); return; }
      select.disabled = false;
      list.forEach(function (c) {
        var label = c.clientId + ' (plays ' + c.organizations[chosenUseCase()] + ')';
        var o = el('option', label);
        o.value = c.clientId;
        select.appendChild(o);
      });
      if (keep) { select.value = keep; }
      fillTargetFromClient();
    }).catch(function (e) { sendSay(e.message, true); });
  }

  function renderPreview(uc, p) {
    releaseBlobs('send');
    var host = $('send-preview');
    clear(host);
    if (!p.seeded) { host.appendChild(el('p', 'Load the data to see the images.', 'empty')); return; }
    var thumbs = el('div', null, 'thumbs');
    host.appendChild(thumbs);
    addThumbs(thumbs, 'send', p.payloads.map(function (x) {
      return {
        path: '/ode/package/images/' + encodeURIComponent(uc) + '/' + encodeURIComponent(x.entryId),
        label: x.entryId,
        meta: x.contentType + ', ' + x.size + ' bytes, sha-256 ' + x.sha256
      };
    }));
  }

  function refreshSeedStatus() {
    var uc = chosenUseCase();
    return api('/ode/package?useCase=' + encodeURIComponent(uc)).then(function (r) { return r.json(); }).then(function (p) {
      renderPreview(uc, p);
      $('seed-status').textContent = p.seeded
        ? useCaseLabel(uc) + ' data is loaded (' + p.resources + ' resources, ' + p.payloads.length + ' image(s), data version ' + String(p.ref).slice(0, 7) + ').'
        : useCaseLabel(uc) + ' data is not loaded yet. Click "Load the data" first.';
    }).catch(function (e) { $('seed-status').textContent = e.message; });
  }

  function seedData() {
    var uc = chosenUseCase();
    $('seed-button').disabled = true;
    sendSay('Loading ' + useCaseLabel(uc) + ' data from the data repository...');
    return apiPost('/ode/admin/seed?useCase=' + encodeURIComponent(uc)).then(function (r) { return r.json(); }).then(function (s) {
      sendSay('Loaded ' + s.resources + ' resources and verified ' + s.payloadsVerified + ' image(s) (data version ' + String(s.ref).slice(0, 7) + ').');
      return refreshSeedStatus();
    }).catch(function (e) { sendSay(e.message, true); }).then(function () { $('seed-button').disabled = false; });
  }

  function renderSendResult(r) {
    var host = $('send-result');
    clear(host);
    var dl = el('dl', null, 'facts');
    var kind = r.outcome === 'delivered' ? 'ok' : 'bad';
    dl.appendChild(el('dt', 'Outcome'));
    var dd = el('dd');
    dd.appendChild(badge(r.outcome, kind));
    dl.appendChild(dd);
    fact(dl, 'Use case', useCaseLabel(r.useCase));
    fact(dl, 'Tester', r.clientId);
    fact(dl, 'Sent to', r.targetBaseUrl);
    fact(dl, 'Their answer', r.targetStatus > 0 ? String(r.targetStatus) : '(no answer)');
    fact(dl, 'Referral id', r.referralId);
    fact(dl, 'Image list (manifest)', r.manifestId);
    fact(dl, 'Images available until', shortTime(r.manifestExpires));
    fact(dl, 'Error', r.error);
    fact(dl, 'Their reply (start)', r.targetResponseExcerpt);
    host.appendChild(dl);
  }

  function sendReferral(ev) {
    ev.preventDefault();
    var c = chosenClient();
    var target = $('send-target').value.trim();
    if (!c) { sendSay('Pick a tester first.', true); return; }
    if (!target) { sendSay('Enter the address to send to.', true); return; }
    if (!window.confirm('Send the ' + useCaseLabel(chosenUseCase()) + ' referral to ' + c.clientId + ' at ' + target + '?')) { return; }
    var body = { clientId: c.clientId, targetBaseUrl: target, useCase: chosenUseCase() };
    var bearer = $('send-bearer').value;
    if (bearer) { body.targetBearerToken = bearer; }
    $('send-button').disabled = true;
    sendSay('Sending...');
    clear($('send-result'));
    apiPost('/ode/send', body).then(function (r) { return r.json(); }).then(function (r) {
      sendSay(r.outcome === 'delivered' ? 'Delivered.' : 'Not delivered: ' + r.outcome + '.', r.outcome !== 'delivered');
      renderSendResult(r);
    }).catch(function (e) { sendSay(e.message, true); }).then(function () {
      $('send-bearer').value = '';
      $('send-button').disabled = false;
    });
  }

  // ---------------------------------------------------------------- sent log

  function showSentImages(s) {
    releaseBlobs('sent');
    var host = $('sent-detail');
    clear(host);
    $('sent-detail-card').hidden = false;
    host.appendChild(el('p', useCaseLabel(s.useCase) + ' referral ' + (s.referralId || '') + ' sent to ' + s.clientId, 'note'));
    api('/ode/manifests/' + encodeURIComponent(s.manifestId)).then(function (r) { return r.json(); }).then(function (m) {
      if (!m.entries.length) { host.appendChild(el('p', 'This referral carried no images.', 'empty')); return; }
      var thumbs = el('div', null, 'thumbs');
      host.appendChild(thumbs);
      addThumbs(thumbs, 'sent', m.entries.map(function (e) {
        return {
          path: '/ode/sends/' + encodeURIComponent(s.sendId) + '/images/' + encodeURIComponent(e.entryId),
          label: e.entryId,
          meta: e.contentType + ', ' + e.size + ' bytes, sha-256 ' + e.sha256
        };
      }));
    }).catch(function (e) { host.appendChild(el('p', e.message, 'message error')); });
  }

  function loadSent() {
    return api('/ode/sends?limit=50').then(function (r) { return r.json(); }).then(function (rows) {
      var host = $('sent-list');
      clear(host);
      if (!rows.length) { host.appendChild(el('p', 'Nothing sent yet.', 'empty')); return; }
      var table = el('table');
      var head = el('tr');
      ['Sent', 'Use case', 'Tester', 'Sent to', 'Outcome', 'Their answer', 'Referral', ''].forEach(function (h) { head.appendChild(el('th', h)); });
      table.appendChild(head);
      rows.forEach(function (s) {
        var row = el('tr');
        row.appendChild(el('td', shortTime(s.at)));
        row.appendChild(el('td', useCaseLabel(s.useCase)));
        row.appendChild(el('td', s.clientId));
        row.appendChild(el('td', s.targetBaseUrl, 'mono'));
        var o = el('td');
        o.appendChild(badge(s.outcome, s.outcome === 'delivered' ? 'ok' : 'bad'));
        row.appendChild(o);
        row.appendChild(el('td', s.targetStatus > 0 ? s.targetStatus : '-'));
        row.appendChild(el('td', s.referralId || '(none)', 'mono'));
        var cell = el('td');
        var open = el('button', 'Images', 'link');
        open.type = 'button';
        open.addEventListener('click', function () { showSentImages(s); });
        cell.appendChild(open);
        row.appendChild(cell);
        table.appendChild(row);
      });
      host.appendChild(table);
    }).catch(function (e) { say(e.message, true); });
  }

  // ---------------------------------------------------------------- wiring

  $('auth-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    token = $('token').value.trim();
    storeToken(token);
    $('detail-card').hidden = true;
    loadList();
    loadClients();
  });

  $('forget').addEventListener('click', function () {
    token = '';
    storeToken('');
    $('token').value = '';
    clear($('list'));
    clear($('detail'));
    $('detail-card').hidden = true;
    clear($('sent-list'));
    clear($('sent-detail'));
    $('sent-detail-card').hidden = true;
    clear($('send-preview'));
    ['received', 'send', 'sent'].forEach(releaseBlobs);
    if (lightboxOpen()) { closeLightbox(); }
    clear($('send-result'));
    clear($('send-client'));
    clients = [];
    $('send-bearer').value = '';
    $('seed-status').textContent = '';
    sendSay('');
    say('Token forgotten.');
  });

  $('refresh').addEventListener('click', loadList);
  $('filter-usecase').addEventListener('change', loadList);
  $('refresh-sent').addEventListener('click', loadSent);
  $('seed-button').addEventListener('click', seedData);
  $('send-form').addEventListener('submit', sendReferral);
  $('send-client').addEventListener('change', fillTargetFromClient);
  $('send-usecase').addEventListener('change', function () { loadClients(); refreshSeedStatus(); });
  VIEWS.forEach(function (v) { $('tab-' + v).addEventListener('click', function () { showView(v); }); });

  token = readStoredToken();
  if (token) { loadList(); loadClients(); }
}());

'use strict';
const $ = id => document.getElementById(id);
let csrf, endpoints = [], events = [], selectedId = null, busy = false;
async function api(path, method = 'GET', body) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && csrf) headers[csrf.csrfHeader] = csrf.csrfToken;
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 || response.redirected) { location.href = '/login'; throw new Error('Please sign in again.'); }
  if (!response.ok) {
    const messages = { 400: 'Check the form values and destination allowlist.', 403: 'Session expired. Refresh and sign in again.', 409: 'This event is active, or its ID was reused with a different body.', 413: 'Payload exceeds the 64 KiB limit.' };
    throw new Error(messages[response.status] || `Request failed (HTTP ${response.status}).`);
  }
  return response.json();
}
function notify(message, error = false) { $('message').textContent = message; $('message').className = error ? 'error' : ''; $('message').hidden = false; }
function node(tag, text, className) { const el = document.createElement(tag); el.textContent = text; if (className) el.className = className; return el; }
function when(date) { return new Date(date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
async function refresh() {
  if (busy) return; busy = true;
  try {
    const old = $('endpoint').value;
    [endpoints, events] = await Promise.all([api('/api/endpoints'), api('/api/events')]);
    $('endpoint').replaceChildren(...endpoints.map(e => { const option = node('option', e.name); option.value = e.id; return option; }));
    if (endpoints.some(e => e.id === old)) $('endpoint').value = old;
    $('total').textContent = events.length;
    $('delivered').textContent = events.filter(e => e.status === 'DELIVERED').length;
    $('pending').textContent = events.filter(e => ['PENDING', 'DELIVERING'].includes(e.status)).length;
    $('dead').textContent = events.filter(e => e.status === 'DEAD').length;
    $('empty').hidden = events.length !== 0;
    $('events').replaceChildren(...events.map(e => {
      const tr = document.createElement('tr'); tr.dataset.id = e.id; tr.tabIndex = 0;
      if (e.id === selectedId) tr.className = 'selected';
      tr.append(node('td', e.sourceId), node('td', endpoints.find(x => x.id === e.endpointId)?.name || e.endpointId));
      const status = document.createElement('td'); status.append(node('span', e.status, `badge ${e.status}`)); tr.append(status);
      tr.append(node('td', String(e.attemptCount)), node('td', when(e.createdAt)));
      tr.onclick = () => action(() => select(e.id));
      tr.onkeydown = ev => { if (ev.key === 'Enter') tr.click(); };
      return tr;
    }));
    for (const id of ['run', 'send', 'configure']) $(id).disabled = !endpoints.length;
    if (selectedId) await select(selectedId);
    await mockStatus();
  } finally { busy = false; }
}
async function select(id) {
  selectedId = id;
  const event = await api(`/api/events/${id}`);
  const attempts = await api(`/api/events/${id}/attempts`);
  $('detail').hidden = false; $('detail-title').textContent = event.sourceId;
  $('detail-meta').textContent = `${event.status} · ${event.attemptCount} attempt(s) in current run · Stored ${new Date(event.createdAt).toLocaleString()}`;
  $('payload').textContent = event.payload;
  $('replay').disabled = !['DELIVERED', 'DEAD'].includes(event.status);
  const receipt = node('li', '202 Accepted · saved to durable inbox'); receipt.append(node('small', when(event.createdAt)));
  $('timeline').replaceChildren(receipt, ...attempts.map(a => {
    const li = node('li', `Attempt ${a.attemptNumber} · ${a.httpStatus ?? 'No response'} · ${a.outcome.replaceAll('_', ' ')}`);
    li.append(node('small', `${when(a.createdAt)} · ${a.durationMs} ms · ${a.detail}`)); return li;
  }));
  if (!attempts.length) $('timeline').append(node('li', 'Waiting for the delivery worker…'));
}
async function mockStatus() {
  const id = $('endpoint').value;
  if (!id) return;
  const endpoint = endpoints.find(e => e.id === id);
  if (endpoint.targetUrl) { $('mock-state').textContent = 'This endpoint forwards to an external receiver. Mock controls do not affect it.'; return; }
  const state = await api(`/api/endpoints/${id}/mock`);
  $('mock-state').textContent = `${state.failuresLeft} simulated failure(s) left · ${state.processedCount} unique business action(s) processed · ${state.delayMs} ms delay`;
}
async function action(fn) { try { await fn(); } catch (error) { notify(error.message, true); } }
function controls() { return { failuresLeft: Number($('failures').value), failureStatus: Number($('failure-code').value), delayMs: Number($('delay').value) }; }
$('endpoint-form').onsubmit = ev => { ev.preventDefault(); action(async () => {
  const created = await api('/api/endpoints', 'POST', { name: $('name').value, targetUrl: $('target').value, maxAttempts: Number($('max-attempts').value), baseDelayMs: Number($('base-delay').value) });
  $('secret-box').hidden = false; $('secret').textContent = created.signingSecret; $('hook-url').textContent = `${location.origin}/hooks/${created.endpoint.id}`;
  await refresh(); $('endpoint').value = created.endpoint.id; await mockStatus(); notify('Endpoint created. Copy its signing secret before leaving this page.');
}); };
$('configure').onclick = () => action(async () => { await api(`/api/endpoints/${$('endpoint').value}/mock`, 'PUT', controls()); await mockStatus(); notify('Receiver controls updated.'); });
$('run').onclick = () => action(async () => {
  const id = $('endpoint').value;
  if (endpoints.find(e => e.id === id).targetUrl) throw new Error('Select a built-in mock endpoint for this demo.');
  await api(`/api/endpoints/${id}/mock`, 'PUT', controls());
  const receipt = await api(`/api/endpoints/${id}/demo`, 'POST', { sourceId: `demo-${crypto.randomUUID()}`, payload: JSON.stringify({ type: 'payment.succeeded', amount: 1499, currency: 'INR', lab: true }, null, 2) });
  selectedId = receipt.eventId; await refresh(); notify('202 Accepted: event saved. Watch the receiver attempts below.'); $('detail').scrollIntoView({ behavior: 'smooth', block: 'center' });
});
$('send').onclick = () => action(async () => { const receipt = await api(`/api/endpoints/${$('endpoint').value}/demo`, 'POST', { sourceId: $('source-id').value, payload: $('custom-payload').value }); selectedId = receipt.eventId; await refresh(); notify(receipt.duplicate ? 'Existing event acknowledged; no new delivery queued.' : '202 Accepted: event saved.'); });
$('replay').onclick = () => action(async () => { await api(`/api/events/${selectedId}/replay`, 'POST'); await refresh(); notify('Replay queued with the original event ID. Check that the receiver processes it only once.'); });
$('endpoint').onchange = () => action(mockStatus);
action(async () => { csrf = await api('/api/session'); await refresh(); setInterval(() => action(refresh), 2000); });

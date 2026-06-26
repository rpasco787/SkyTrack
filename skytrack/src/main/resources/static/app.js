'use strict';

// ── State ──────────────────────────────────────────────────────────────────
let airportCoords = {};    // IATA → {lat, lon, name}  (loaded once at init)
const markers = new Map(); // IATA → L.circleMarker     (diff-updated each poll)

let selectedIata = null;
let pollInterval  = null;
let pollStartedAt = null;

// ── DOM refs ───────────────────────────────────────────────────────────────
const $lbList      = document.getElementById('lb-list');
const $lbCount     = document.getElementById('lb-count');
const $updatedLabel= document.getElementById('updated-label');
const $refreshBtn  = document.getElementById('refresh-btn');
const $flightForm  = document.getElementById('flight-form');
const $callsignInput = document.getElementById('callsign-input');
const $flightResult  = document.getElementById('flight-result');
const $detailPanel   = document.getElementById('detail-panel');
const $detailContent = document.getElementById('detail-content');
const $detailClose   = document.getElementById('detail-close');
const $emptyHint     = document.getElementById('empty-hint');
const $connLost      = document.getElementById('conn-lost');

// ── Map ────────────────────────────────────────────────────────────────────
const map = L.map('map', {
  center: [39.5, -98.5],
  zoom: 4,
  zoomControl: false,
});

L.control.zoom({ position: 'bottomleft' }).addTo(map);

L.tileLayer(
  'https://{s}.basemaps.cartocdn.com/dark_matter_all/{z}/{x}/{y}{r}.png',
  {
    attribution:
      '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors ' +
      '&copy; <a href="https://carto.com/attributions">CARTO</a>',
    subdomains: 'abcd',
    maxZoom: 19,
  }
).addTo(map);

// Dismiss detail panel when clicking the empty map
map.on('click', () => closeDetail());

// ── Score helpers ──────────────────────────────────────────────────────────
function scoreColor(score) {
  if (score < 25) return '#3fb950';
  if (score < 50) return '#d29922';
  if (score < 75) return '#f78166';
  return '#f85149';
}

function scoreClass(score) {
  if (score < 25) return 'score-green';
  if (score < 50) return 'score-amber';
  if (score < 75) return 'score-orange';
  return 'score-red';
}

function trendArrow(trendDirection) {
  if (trendDirection >  0.05) return { sym: '▲', cls: 'score-red'   };
  if (trendDirection < -0.05) return { sym: '▼', cls: 'score-green' };
  return { sym: '–', cls: 'score-muted' };
}

function markerRadius(activeDelayCount) {
  return Math.min(Math.max(6 + activeDelayCount * 1.4, 6), 28);
}

// ── Disruption poll ────────────────────────────────────────────────────────
async function pollDisruptions() {
  let data;
  try {
    const res = await fetch('/airports/disruptions?limit=50');
    if (!res.ok) throw new Error(res.status);
    data = await res.json();
    $connLost.hidden = true;
  } catch {
    $connLost.hidden = false;
    return;
  }

  pollStartedAt = Date.now();
  renderMarkers(data);
  renderLeaderboard(data.slice(0, 10));

  const isEmpty = !data || data.length === 0;
  $emptyHint.hidden = !isEmpty;

  // Refresh open detail panel
  if (selectedIata) fetchDetail(selectedIata);
}

// ── Markers ────────────────────────────────────────────────────────────────
function renderMarkers(disruptions) {
  const seen = new Set();

  disruptions.forEach((d, i) => {
    const iata  = d.airportIata;
    const coord = airportCoords[iata];
    seen.add(iata);

    const color  = scoreColor(d.score);
    const radius = markerRadius(d.activeDelayCount ?? 0);
    const isTop  = i === 0; // glow ring on #1

    const tooltipText = `${iata} · ${d.score.toFixed(1)}`;

    if (markers.has(iata)) {
      // Diff-update existing marker in place — no flicker
      const m = markers.get(iata);
      m.setStyle({ fillColor: color, color: isTop ? color : 'rgba(0,0,0,0.35)', weight: isTop ? 2.5 : 1 });
      m.setRadius(radius);
      m.setTooltipContent(tooltipText);
      // Keep glow class in sync as #1 position changes across polls
      const el = m.getElement();
      if (el) {
        if (isTop) L.DomUtil.addClass(el, 'marker-top');
        else L.DomUtil.removeClass(el, 'marker-top');
      }
    } else {
      if (!coord) return; // no coordinates — skip map pin but leaderboard still shows it

      const m = L.circleMarker([coord.lat, coord.lon], {
        radius,
        fillColor: color,
        fillOpacity: 0.82,
        color: isTop ? color : 'rgba(0,0,0,0.35)',
        weight: isTop ? 2.5 : 1,
        className: isTop ? 'marker-top' : '',
      }).addTo(map);

      m.bindTooltip(tooltipText, {
        permanent: false, direction: 'top', opacity: 0.92,
        className: 'skytrack-tooltip',
      });

      m.on('click', (e) => {
        L.DomEvent.stopPropagation(e);
        selectAirport(iata);
      });

      markers.set(iata, m);
    }
  });

  // Remove stale markers (airports that dropped out of top-50)
  markers.forEach((m, iata) => {
    if (!seen.has(iata)) {
      m.remove();
      markers.delete(iata);
    }
  });
}

// ── Leaderboard ────────────────────────────────────────────────────────────
function renderLeaderboard(top10) {
  $lbCount.textContent = top10.length ? `${top10.length}` : '—';
  $lbList.innerHTML = '';

  top10.forEach((d, i) => {
    const iata  = d.airportIata;
    const color = scoreColor(d.score);
    const trend = trendArrow(d.trendDirection ?? 0);

    const li = document.createElement('li');
    li.className = 'lb-row' + (iata === selectedIata ? ' is-selected' : '') + (i === 0 ? ' lb-row--top' : '');
    li.dataset.iata = iata;
    li.innerHTML = `
      <span class="lb-rank">${i + 1}</span>
      <span class="lb-dot" style="background:${color}"></span>
      <span class="lb-iata">${iata}</span>
      <span class="lb-score ${scoreClass(d.score)}">${d.score.toFixed(1)}</span>
      <span class="lb-trend ${trend.cls}">${trend.sym}</span>
    `;
    li.addEventListener('click', () => selectAirport(iata));
    $lbList.appendChild(li);
  });
}

// ── Airport selection ──────────────────────────────────────────────────────
function selectAirport(iata) {
  selectedIata = iata;

  // Highlight selected leaderboard row
  document.querySelectorAll('.lb-row').forEach(el => {
    el.classList.toggle('is-selected', el.dataset.iata === iata);
  });

  // Pan map to airport if coords known
  const coord = airportCoords[iata];
  if (coord) map.panTo([coord.lat, coord.lon], { animate: true, duration: 0.5 });

  fetchDetail(iata);
}

// ── Detail panel ───────────────────────────────────────────────────────────
async function fetchDetail(iata) {
  let status;
  try {
    const res = await fetch(`/airports/${iata}/status`);
    if (!res.ok) throw new Error(res.status);
    status = await res.json();
  } catch {
    renderDetailError(iata);
    return;
  }
  renderDetail(iata, status);
}

function renderDetail(iata, status) {
  const coord = airportCoords[iata];
  const name  = coord ? coord.name : iata;
  const score = status.score?.score ?? 0;
  const color = scoreColor(score);

  let html = `
    <div class="detail-header" style="--hdr-color:${color}">
      <div class="detail-header__iata" style="color:${color}">${iata}</div>
      <div class="detail-header__name">${name}</div>
      <div class="detail-header__score-row">
        <span class="detail-score-val" style="color:${color}">${score.toFixed(1)}</span>
        <span class="detail-score-label">disruption score</span>
      </div>
    </div>`;

  // Weather
  const wx = status.weather;
  html += `<div class="detail-section">
    <div class="detail-section__title">Weather</div>`;

  if (wx) {
    const cat = wx.flightCategory || 'VFR';
    html += `
    <div class="wx-row">
      <span class="wx-label">Category</span>
      <span class="wx-cat-badge ${cat}">${cat}</span>
    </div>
    <div class="wx-row">
      <span class="wx-label">Visibility</span>
      <span class="wx-val">${wx.visibilityStatuteMiles != null ? wx.visibilityStatuteMiles + ' sm' : '—'}</span>
    </div>
    <div class="wx-row">
      <span class="wx-label">Ceiling</span>
      <span class="wx-val">${wx.ceilingFeet != null ? wx.ceilingFeet.toLocaleString() + ' ft' : '—'}</span>
    </div>
    <div class="wx-row">
      <span class="wx-label">Wind</span>
      <span class="wx-val">${wx.windSpeedKnots != null ? wx.windSpeedKnots + ' kt' : '—'}${wx.windGustKnots ? ' G' + wx.windGustKnots : ''}</span>
    </div>`;
    if (wx.rawMetar) {
      html += `<div class="metar-raw">${escHtml(wx.rawMetar)}</div>`;
    }
  } else {
    html += `<p class="no-data-hint">weather unavailable</p>`;
  }
  html += `</div>`;

  // Cascades
  const cascades = status.cascades || [];
  html += `<div class="detail-section">
    <div class="detail-section__title">Cascades (${cascades.length})</div>`;

  if (cascades.length) {
    cascades.forEach(c => {
      const delayMin = c.currentDelaySeconds != null
        ? Math.round(c.currentDelaySeconds / 60) + ' min'
        : '—';
      html += `
      <div class="cascade-row">
        <span class="cascade-call">${escHtml(c.sourceCallsign)}</span>
        <span class="cascade-arr">→ ${escHtml(c.arrivalAirportIata)}</span>
        <span class="cascade-delay ${scoreClass(Math.min((c.currentDelaySeconds || 0) / 36, 100))}">${delayMin}</span>
      </div>`;
    });
  } else {
    html += `<p class="no-data-hint">no active cascades</p>`;
  }
  html += `</div>`;

  $detailContent.innerHTML = html;
  $detailPanel.classList.add('is-open');
}

function renderDetailError(iata) {
  $detailContent.innerHTML = `
    <div class="detail-header">
      <div class="detail-header__iata">${iata}</div>
      <div class="detail-header__name">status unavailable</div>
    </div>`;
  $detailPanel.classList.add('is-open');
}

function closeDetail() {
  selectedIata = null;
  $detailPanel.classList.remove('is-open');
  document.querySelectorAll('.lb-row').forEach(el => el.classList.remove('is-selected'));
}

// ── Flight search ──────────────────────────────────────────────────────────
$flightForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const callsign = $callsignInput.value.trim().toUpperCase();
  if (!callsign) return;

  $flightResult.hidden = false;
  $flightResult.innerHTML = `<span class="fr-label">searching…</span>`;

  try {
    const res = await fetch(`/flights/${callsign}`);
    if (res.status === 404) {
      $flightResult.innerHTML = `<span class="fr-miss">no active track for ${escHtml(callsign)}</span>`;
      return;
    }
    if (!res.ok) throw new Error(res.status);
    const track = await res.json();
    renderFlightResult(track);
  } catch {
    $flightResult.innerHTML = `<span class="fr-miss">request failed</span>`;
  }
});

function renderFlightResult(track) {
  const state    = track.state || '—';
  const alt      = track.baroAltitude != null ? Math.round(track.baroAltitude).toLocaleString() + ' ft' : '—';
  const nearest  = track.nearestAirportIcao || '—';

  $flightResult.innerHTML = `
    <div><span class="fr-label">callsign </span><span class="fr-val">${escHtml(track.callsign)}</span></div>
    <div><span class="fr-label">state    </span><span class="fr-val">${escHtml(state)}</span></div>
    <div><span class="fr-label">altitude </span><span class="fr-val">${alt}</span></div>
    <div><span class="fr-label">nearest  </span><span class="fr-val">${escHtml(nearest)}</span></div>`;

  if (track.latitude != null && track.longitude != null) {
    map.setView([track.latitude, track.longitude], 6, { animate: true });
    // Temporary pin — removed on next search or panel close
    if (window._flightPin) window._flightPin.remove();
    window._flightPin = L.circleMarker([track.latitude, track.longitude], {
      radius: 7,
      fillColor: '#4d9de0',
      fillOpacity: 0.9,
      color: '#fff',
      weight: 1.5,
    }).addTo(map).bindTooltip(track.callsign, { permanent: false, direction: 'top' });
  }
}

// ── Refresh controls ───────────────────────────────────────────────────────
$refreshBtn.addEventListener('click', () => {
  $refreshBtn.classList.add('is-spinning');
  setTimeout(() => $refreshBtn.classList.remove('is-spinning'), 500);
  pollDisruptions();
  pollStartedAt = Date.now();
});

$detailClose.addEventListener('click', closeDetail);

// Tick the "updated Ns ago" label between polls
setInterval(() => {
  if (pollStartedAt == null) return;
  const secs = Math.round((Date.now() - pollStartedAt) / 1000);
  $updatedLabel.textContent = `updated ${secs}s ago`;
}, 1000);

// ── Utility ────────────────────────────────────────────────────────────────
function escHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// ── Init ───────────────────────────────────────────────────────────────────
async function init() {
  try {
    const res = await fetch('/airports.json');
    airportCoords = await res.json();
  } catch {
    console.warn('Failed to load airports.json');
  }

  await pollDisruptions();
  pollInterval = setInterval(pollDisruptions, 5000);
}

document.addEventListener('DOMContentLoaded', init);

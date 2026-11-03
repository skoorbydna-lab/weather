// Ride Weather – rain alert worker (Cloudflare Workers, free plan)
//
// Every 10 minutes (cron) it checks Open-Meteo's 15-minute rain forecast for
// your latest location and sends a push via Pushover (or ntfy) if rain is due in the next hour.
//
// Bindings / variables to set in the Cloudflare dashboard:
//   KV namespace binding:  STATE
//   Variable  KEY          a secret password (the app and OwnTracks send it)
//   Variable  PUSHOVER_USER / PUSHOVER_TOKEN  (or NTFY_TOPIC) – or fill in CONFIG below
//   Variable  APP_URL      (optional) e.g. https://skoorbydna-lab.github.io/weather/
//   Variable  HOME_LAT / HOME_LON  (optional) used until a location has been sent
// Cron trigger:  */10 * * * *

// ---- EASY SETUP: fill these in here instead of using "Variables and Secrets" ----
const CONFIG = {
  KEY: '',             // your secret key (the app uses this)
  PUSHOVER_USER: '',   // your Pushover User Key (pushover.net, top right after logging in)
  PUSHOVER_TOKEN: '',  // your Pushover app's API Token (from "Create an Application")
  NTFY_TOPIC: '',      // (alternative to Pushover) ntfy topic
  NTFY_TOKEN: '',      // (optional) ntfy access token
  APP_URL: 'https://skoorbydna-lab.github.io/weather/',
};
// ---------------------------------------------------------------------------------

function withConfig(env) {
  const e = Object.create(env);
  for (const k of Object.keys(CONFIG)) if (!env[k] && CONFIG[k]) e[k] = CONFIG[k];
  return e;
}

const DEFAULTS = { enabled: true, thresh: 0.1, quietStart: 22, quietEnd: 6, agree: 2, summary: true, summaryHour: 7 };
// models that vote on whether rain is coming (all have good UK detail)
const ALERT_MODELS = [
  { id: 'dmi_seamless',  name: 'DMI' },
  { id: 'icon_seamless', name: 'ICON' },
  { id: 'ukmo_seamless', name: 'Met Office' },
];
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
};

const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...CORS, 'Content-Type': 'application/json' } });

async function getState(env) {
  return (await env.STATE.get('state', 'json')) || {};
}
async function putState(env, st) {
  await env.STATE.put('state', JSON.stringify(st));
}

function distKm(a, b, c, d) {
  const R = 6371, r = Math.PI / 180;
  const x = Math.sin(((c - a) * r) / 2) ** 2 + Math.cos(a * r) * Math.cos(c * r) * Math.sin(((d - b) * r) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

function inQuiet(h, start, end) {
  if (start === end) return false;
  return start < end ? h >= start && h < end : h >= start || h < end;
}

async function notify(env, title, body, tags = 'cloud_with_rain', priority = 'high') {
  if (env.PUSHOVER_USER && env.PUSHOVER_TOKEN) return pushover(env, title, body, priority);
  return ntfy(env, title, body, tags, priority);
}

async function pushover(env, title, message, priority) {
  const form = new URLSearchParams({
    token: env.PUSHOVER_TOKEN,
    user: env.PUSHOVER_USER,
    title,
    message,
    priority: priority === 'high' ? '1' : '0',
  });
  if (env.APP_URL) { form.set('url', env.APP_URL); form.set('url_title', 'Open Ride Weather'); }
  try {
    const r = await fetch('https://api.pushover.net/1/messages.json', { method: 'POST', body: form });
    const j = await r.json().catch(() => ({}));
    const ok = r.ok && j.status === 1;
    return { ok, service: 'pushover', status: r.status, error: ok ? undefined : (j.errors || []).join('; ') || 'unknown error' };
  } catch (e) {
    return { ok: false, service: 'pushover', error: String(e) };
  }
}

async function ntfy(env, title, body, tags, priority) {
  if (!env.NTFY_TOPIC) return { ok: false, error: 'No alert service set: fill in PUSHOVER_USER and PUSHOVER_TOKEN' };
  const headers = { Title: title, Tags: tags, Priority: priority };
  if (env.APP_URL) headers.Click = env.APP_URL;
  if (env.NTFY_TOKEN) headers.Authorization = 'Bearer ' + env.NTFY_TOKEN;
  try {
    const r = await fetch(`https://ntfy.sh/${encodeURIComponent(env.NTFY_TOPIC)}`, { method: 'POST', body, headers });
    const text = r.ok ? '' : (await r.text()).slice(0, 200);
    return { ok: r.ok, service: 'ntfy', status: r.status, topic: env.NTFY_TOPIC, error: text || undefined };
  } catch (e) {
    return { ok: false, service: 'ntfy', topic: env.NTFY_TOPIC, error: String(e) };
  }
}

// ---------- morning summary ----------
const COMPASS = ['N','NE','E','SE','S','SW','W','NW'];
const dirName = d => d == null ? '' : COMPASS[Math.round((d % 360) / 45) % 8];
const WORDS = {0:'Clear',1:'Mostly clear',2:'Partly cloudy',3:'Cloudy',45:'Fog',48:'Fog',51:'Drizzle',53:'Drizzle',55:'Drizzle',61:'Light rain',63:'Rain',65:'Heavy rain',
  71:'Light snow',73:'Snow',75:'Heavy snow',80:'Showers',81:'Showers',82:'Heavy showers',95:'Thunderstorms',96:'Thunderstorms',99:'Thunderstorms'};

async function getLoc(env, st){
  if (st.loc) return st.loc;
  if (env.HOME_LAT && env.HOME_LON) return { lat: +env.HOME_LAT, lon: +env.HOME_LON, ts: 0, source: 'home' };
  return null;
}

async function summary(env, force) {
  const st = await getState(env);
  const s = { ...DEFAULTS, ...(st.settings || {}) };
  if (!s.summary && !force) return { skipped: 'summary off' };
  const loc = await getLoc(env, st);
  if (!loc) return { skipped: 'no location yet' };
  const u = `https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}&timezone=auto&wind_speed_unit=mph&forecast_days=1` +
    `&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code,cloud_cover,wind_speed_10m,wind_gusts_10m,wind_direction_10m,is_day` +
    `&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,sunrise,sunset`;
  let r = await fetch(u);
  if (r.status === 429) { await new Promise(res => setTimeout(res, 20000)); r = await fetch(u); }
  if (!r.ok) return { error: 'open-meteo ' + r.status };
  const d = await r.json(), off = d.utc_offset_seconds || 0;
  const today = new Date(Date.now() + off * 1000).toISOString().slice(0, 10);
  if (!force && st.lastSummary === today) return { skipped: 'already sent today' };
  const h = d.hourly, day = [];
  h.time.forEach((t, i) => { const hr = +t.slice(11, 13); if (hr >= 7 && hr <= 19) day.push(i); });
  const max = d.daily.temperature_2m_max[0], min = d.daily.temperature_2m_min[0];
  const rainDay = day.reduce((a, i) => a + (h.precipitation[i] || 0), 0);
  const wetHrs = day.filter(i => (h.precipitation[i] || 0) >= 0.2).map(i => +h.time[i].slice(11, 13));
  const pmax = d.daily.precipitation_probability_max ? d.daily.precipitation_probability_max[0] : null;
  const wMax = Math.max(...day.map(i => h.wind_speed_10m[i] || 0)), gMax = Math.max(...day.map(i => h.wind_gusts_10m[i] || 0));
  const dirs = day.map(i => h.wind_direction_10m[i]).filter(v => v != null);
  const sx = dirs.reduce((a, v) => a + Math.sin(v * Math.PI / 180), 0), cx = dirs.reduce((a, v) => a + Math.cos(v * Math.PI / 180), 0);
  const mainDir = dirs.length ? dirName((Math.atan2(sx, cx) * 180 / Math.PI + 360) % 360) : '';
  const cc = day.map(i => h.cloud_cover[i]).filter(v => v != null), ccAvg = cc.length ? cc.reduce((a, b) => a + b, 0) / cc.length : null;
  let look;
  if (wetHrs.length >= 3 || rainDay >= 1) look = wetHrs.length >= 7 ? 'Wet day' : 'Showers';
  else look = ccAvg == null ? 'Dry' : ccAvg < 15 ? 'Sunny' : ccAvg < 40 ? 'Mostly sunny' : ccAvg < 75 ? 'Sunny spells' : 'Cloudy, dry';
  // rain timing
  let rainTxt = 'Dry';
  if (rainDay >= 0.2) {
    const span = wetHrs.length ? `mainly ${String(wetHrs[0]).padStart(2,'0')}–${String(wetHrs[wetHrs.length-1]+1).padStart(2,'0')}` : 'light';
    rainTxt = `${rainDay.toFixed(1)} mm, ${span}`;
  }
  // best 2-hour daylight ride window (same idea as the app's rating)
  const score = i => {
    const p = h.precipitation[i] || 0, pp = h.precipitation_probability ? h.precipitation_probability[i] : null;
    const w = h.wind_speed_10m[i] || 0, g = h.wind_gusts_10m[i] || 0, t = h.apparent_temperature[i] ?? h.temperature_2m[i];
    let sc = p * 3 + (pp != null ? pp * 0.02 : (p > 0 ? 1 : 0)) + Math.max(0, w - 8) * 0.25 + Math.max(0, g - 22) * 0.15;
    if (t != null && t < 3) sc += (3 - t) * 0.4;
    if (h.is_day && h.is_day[i] === 0) sc += 3;
    return sc;
  };
  let best = null;
  for (let k = 0; k + 1 < h.time.length; k++) {
    if (h.is_day && (h.is_day[k] === 0 || h.is_day[k + 1] === 0)) continue;
    const v = (score(k) + score(k + 1)) / 2;
    if (!best || v < best.v) best = { k, v };
  }
  const ride = best ? `${h.time[best.k].slice(11, 16)}–${String(+h.time[best.k].slice(11, 13) + 2).padStart(2, '0')}:00` : '–';
  const sun = `${d.daily.sunrise[0].slice(11, 16)}–${d.daily.sunset[0].slice(11, 16)}`;
  const body = [
    `${look}, ${Math.round(max)}° / ${Math.round(min)}°`,
    `Rain: ${rainTxt}${pmax != null ? ` (${pmax}% chance)` : ''}`,
    `Wind: ${Math.round(wMax)} mph ${mainDir}, gusts ${Math.round(gMax)}`,
    `Best ride: ${ride}`,
    `Daylight: ${sun}`,
  ].join('\n');
  const sent = await notify(env, `Today's ride weather`, body, 'sunrise', 'default');
  if (!force) { st.lastSummary = today; await putState(env, st); }
  return { sent: sent.ok, body };
}

async function maybeSummary(env) {
  const st = await getState(env);
  const s = { ...DEFAULTS, ...(st.settings || {}) };
  if (!s.summary) return;
  // the forecast's own timezone decides "7 am"; cheap check first using London time
  const london = new Date(new Date().toLocaleString('en-US', { timeZone: 'Europe/London' }));
  if (london.getHours() !== s.summaryHour) return;
  const today = london.toLocaleDateString('en-CA');
  if (st.lastSummary === today) return;
  await summary(env, false);
}

async function check(env, force) {
  const st = await getState(env);
  const s = { ...DEFAULTS, ...(st.settings || {}) };
  if (!s.enabled && !force) return { skipped: 'alerts off' };

  let loc = st.loc;
  if (!loc && env.HOME_LAT && env.HOME_LON) loc = { lat: +env.HOME_LAT, lon: +env.HOME_LON, ts: 0, source: 'home' };
  if (!loc) return { skipped: 'no location yet' };

  const u = `https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}` +
    `&minutely_15=precipitation&forecast_minutely_15=6&past_minutely_15=1&timezone=auto` +
    `&models=${ALERT_MODELS.map(m => m.id).join(',')}`;
  let r = await fetch(u);
  if (r.status === 429) { await new Promise(res => setTimeout(res, 20000)); r = await fetch(u); }   // busy: one retry
  if (!r.ok) return { error: 'open-meteo ' + r.status };
  const d = await r.json();
  const off = d.utc_offset_seconds || 0, now = Date.now();
  const localHour = new Date(now + off * 1000).getUTCHours();
  if (!force && inQuiet(localHour, s.quietStart, s.quietEnd)) return { skipped: 'quiet hours' };

  // each model votes: does any 15-minute slot in the next hour reach the threshold?
  const m = d.minutely_15 || { time: [] };
  const inHour = m.time.map((t, i) => ({ t, i, ms: Date.parse(t + ':00Z') - off * 1000 }))
    .filter(x => x.ms + 15 * 60e3 > now && x.ms < now + 60 * 60e3);
  const votes = ALERT_MODELS.map(mod => {
    const arr = m[`precipitation_${mod.id}`] || m.precipitation;
    if (!arr) return null;
    const vals = inHour.map(x => ({ ...x, p: arr[x.i] }));
    if (vals.every(v => v.p == null)) return null;             // model has no data here
    const wet = vals.find(v => (v.p || 0) >= s.thresh);
    return { name: mod.name, wet: !!wet, first: wet, total: vals.reduce((a, v) => a + (v.p || 0), 0) };
  }).filter(Boolean);
  if (!votes.length) return { error: 'no model data' };
  const need = Math.min(s.agree || 2, votes.length);
  const yes = votes.filter(v => v.wet);
  const summary = votes.map(v => `${v.name} ${v.wet ? 'rain' : 'dry'}`).join(', ');

  if (yes.length < need) {
    if (st.raining) { st.raining = false; await putState(env, st); }
    if (force) await notify(env, 'Dry for the next hour', `No rain expected at your location in the next hour (${summary}).`, 'white_check_mark', 'default');
    return { dry: true, models: summary };
  }

  if (!force && (st.raining || now - (st.lastAlert || 0) < 45 * 60e3)) return { alreadyAlerted: true, models: summary };

  const first = yes.map(v => v.first).sort((a, b) => a.ms - b.ms)[Math.floor((yes.length - 1) / 2)];   // middle start time of the agreeing models
  const total = yes.reduce((a, v) => a + v.total, 0) / yes.length;
  const when = first.ms <= now ? 'now' : 'from about ' + first.t.slice(11, 16);
  let where = '';
  if (loc.source === 'home') where = ' (home)';
  else if (loc.ts) {
    const mins = Math.round((now - loc.ts) / 60000);
    where = mins < 2 ? '' : ` Location from ${mins < 90 ? mins + ' min' : Math.round(mins / 60) + ' h'} ago.`;
  }
  await notify(env, 'Rain coming', `Rain ${when} - about ${total.toFixed(1)} mm in the next hour (${yes.length} of ${votes.length} models agree).${where}`);
  st.raining = true;
  st.lastAlert = now;
  await putState(env, st);
  return { alerted: true, when, total, agreeing: `${yes.length} of ${votes.length}`, models: summary };
}

export default {
  async fetch(req, env) {
    env = withConfig(env);
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);

    // Key from ?key=, or from the password of Basic auth (OwnTracks)
    let key = url.searchParams.get('key');
    const auth = req.headers.get('Authorization') || '';
    if (!key && auth.startsWith('Basic ')) {
      try { key = atob(auth.slice(6)).split(':').slice(1).join(':'); } catch (e) {}
    }
    if (!env.KEY || key !== env.KEY) return json({ error: 'wrong key' }, 401);

    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (path === '/loc' && req.method === 'POST') {
      const b = await req.json().catch(() => ({}));
      const owntracks = typeof b._type === 'string';
      const ack = () => (owntracks ? json([]) : null);
      if (owntracks && b._type !== 'location') return json([]);
      const lat = +b.lat, lon = +b.lon;
      if (!isFinite(lat) || !isFinite(lon)) return ack() || json({ error: 'no lat/lon' }, 400);
      const ts = b.tst ? b.tst * 1000 : Date.now();
      const st = await getState(env);
      const p = st.loc;
      // Only write if moved > 300 m or 10+ min since last save (keeps KV writes low)
      if (!p || p.source === 'home' || distKm(p.lat, p.lon, lat, lon) > 0.3 || ts - p.ts > 10 * 60e3) {
        st.loc = { lat: +lat.toFixed(4), lon: +lon.toFixed(4), ts, source: owntracks ? 'owntracks' : 'app' };
        await putState(env, st);
      }
      return ack() || json({ ok: true, loc: st.loc });
    }

    if (path === '/settings' && req.method === 'POST') {
      const b = await req.json().catch(() => ({}));
      const st = await getState(env);
      const s = { ...DEFAULTS, ...(st.settings || {}) };
      if (typeof b.enabled === 'boolean') s.enabled = b.enabled;
      if (isFinite(+b.thresh) && +b.thresh > 0) s.thresh = +b.thresh;
      if (Number.isInteger(+b.quietStart) && +b.quietStart >= 0 && +b.quietStart < 24) s.quietStart = +b.quietStart;
      if (Number.isInteger(+b.quietEnd) && +b.quietEnd >= 0 && +b.quietEnd < 24) s.quietEnd = +b.quietEnd;
      if ([1, 2, 3].includes(+b.agree)) s.agree = +b.agree;
      if (typeof b.summary === 'boolean') s.summary = b.summary;
      if (Number.isInteger(+b.summaryHour) && +b.summaryHour >= 4 && +b.summaryHour <= 11) s.summaryHour = +b.summaryHour;
      st.settings = s;
      await putState(env, st);
      return json({ ok: true, settings: s });
    }

    if (path === '/status') {
      const st = await getState(env);
      return json({ ok: true, settings: { ...DEFAULTS, ...(st.settings || {}) }, loc: st.loc || null, lastAlert: st.lastAlert || null, raining: !!st.raining });
    }

    if (path === '/test') {
      const r = await notify(env, 'Test alert', 'Alerts are working - rain warnings will arrive here.', 'bell', 'default');
      return json(r);
    }

    if (path === '/check') return json(await check(env, true));
    if (path === '/summary') return json(await summary(env, true));

    return json({ error: 'not found' }, 404);
  },

  async scheduled(event, env, ctx) {
    env = withConfig(env);
    ctx.waitUntil(Promise.all([check(env, false), maybeSummary(env)]));
  },
};

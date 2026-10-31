// Ride Weather – rain alert worker (Cloudflare Workers, free plan)
//
// Every 10 minutes (cron) it checks Open-Meteo's 15-minute rain forecast for
// your latest location and sends a push via Pushover (or ntfy) if rain is due in the next hour.
//
// Bindings / variables to set in the Cloudflare dashboard:
//   KV namespace binding:  STATE
//   Variable  KEY          a secret password (the app and OwnTracks send it)
//   Variable  PUSHOVER_USER / PUSHOVER_TOKEN  (or NTFY_TOPIC) – or fill in CONFIG below
//   Variable  APP_URL      (optional) e.g. https://skoorbydna-lab.github.io/ride-weather/
//   Variable  HOME_LAT / HOME_LON  (optional) used until a location has been sent
// Cron trigger:  */10 * * * *

// ---- EASY SETUP: fill these in here instead of using "Variables and Secrets" ----
const CONFIG = {
  KEY: '',             // your secret key (the app uses this)
  PUSHOVER_USER: '',   // your Pushover User Key (pushover.net, top right after logging in)
  PUSHOVER_TOKEN: '',  // your Pushover app's API Token (from "Create an Application")
  NTFY_TOPIC: '',      // (alternative to Pushover) ntfy topic
  NTFY_TOKEN: '',      // (optional) ntfy access token
  APP_URL: 'https://skoorbydna-lab.github.io/ride-weather/',
};
// ---------------------------------------------------------------------------------

function withConfig(env) {
  const e = Object.create(env);
  for (const k of Object.keys(CONFIG)) if (!env[k] && CONFIG[k]) e[k] = CONFIG[k];
  return e;
}

const DEFAULTS = { enabled: true, thresh: 0.1, quietStart: 22, quietEnd: 6 };
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

async function check(env, force) {
  const st = await getState(env);
  const s = { ...DEFAULTS, ...(st.settings || {}) };
  if (!s.enabled && !force) return { skipped: 'alerts off' };

  let loc = st.loc;
  if (!loc && env.HOME_LAT && env.HOME_LON) loc = { lat: +env.HOME_LAT, lon: +env.HOME_LON, ts: 0, source: 'home' };
  if (!loc) return { skipped: 'no location yet' };

  const u = `https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}` +
    `&minutely_15=precipitation&forecast_minutely_15=6&past_minutely_15=1&timezone=auto`;
  const r = await fetch(u);
  if (!r.ok) return { error: 'open-meteo ' + r.status };
  const d = await r.json();
  const off = d.utc_offset_seconds || 0, now = Date.now();
  const localHour = new Date(now + off * 1000).getUTCHours();
  if (!force && inQuiet(localHour, s.quietStart, s.quietEnd)) return { skipped: 'quiet hours' };

  const m = d.minutely_15 || { time: [], precipitation: [] };
  const slots = m.time
    .map((t, i) => ({ t, ms: Date.parse(t + ':00Z') - off * 1000, p: m.precipitation[i] || 0 }))
    .filter(x => x.ms + 15 * 60e3 > now && x.ms < now + 60 * 60e3);
  const wet = slots.find(x => x.p >= s.thresh);

  if (!wet) {
    if (st.raining) { st.raining = false; await putState(env, st); }
    if (force) await notify(env, 'Dry for the next hour', 'No rain expected at your location in the next hour.', 'white_check_mark', 'default');
    return { dry: true, slots };
  }

  if (!force && (st.raining || now - (st.lastAlert || 0) < 45 * 60e3)) return { alreadyAlerted: true, slots };

  const total = slots.reduce((a, x) => a + x.p, 0);
  const when = wet.ms <= now ? 'now' : 'from about ' + wet.t.slice(11, 16);
  let where = '';
  if (loc.source === 'home') where = ' (home)';
  else if (loc.ts) {
    const mins = Math.round((now - loc.ts) / 60000);
    where = mins < 2 ? '' : ` Location from ${mins < 90 ? mins + ' min' : Math.round(mins / 60) + ' h'} ago.`;
  }
  await notify(env, 'Rain coming', `Rain ${when} - ${total.toFixed(1)} mm in the next hour.${where}`);
  st.raining = true;
  st.lastAlert = now;
  await putState(env, st);
  return { alerted: true, when, total, slots };
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

    return json({ error: 'not found' }, 404);
  },

  async scheduled(event, env, ctx) {
    env = withConfig(env);
    ctx.waitUntil(check(env, false));
  },
};

# Ride Weather – phone rain alerts setup

The web app (index.html) runs on GitHub Pages. Rain alerts come from a small
Cloudflare Worker (worker.js). Every 10 minutes it checks Open-Meteo for your
latest location. If rain is due within the hour, it sends a notification
through ntfy.

Keep your **topic name** and **secret key** private. Don't commit them to this repo.

## 1. ntfy (on your phone)
1. Install **ntfy** from the Play Store / App Store.
2. Tap **+** and subscribe to your private topic name (e.g. `ride-rain-xxxxxxxxxx`).

## 2. Cloudflare Worker (free account at dash.cloudflare.com)
1. **Workers & Pages → Create → Create Worker** (Hello World). Name it `ride-rain` and deploy.
2. **Edit code**: delete everything, paste in the contents of `worker.js`, then **Deploy**.
3. **Storage & Databases → KV → Create**. Name it `ride-rain-state`.
4. Back in the worker, go to **Settings → Bindings → Add → KV namespace**.
   Set the variable name to `STATE` and the namespace to `ride-rain-state`.
5. **Settings → Variables and Secrets**, add:
   - `KEY` (type Secret): your secret key
   - `NTFY_TOPIC`: your topic name
   - `APP_URL`: `https://skoorbydna-lab.github.io/ride-weather/`
   - optional `HOME_LAT` and `HOME_LON`: used until a location has been sent
6. **Settings → Trigger events → Cron triggers → Add**: `*/10 * * * *`
7. Copy the worker's address, e.g. `https://ride-rain.<your-subdomain>.workers.dev`

## 3. Connect the app
In Ride Weather, tap **⚙**, paste the worker address and key, then tap **Save & connect**.
Then tap **Send test alert**. You should get a notification in ntfy.

The app sends your location to the worker each time you open it. The worker
always checks the last location it received.

## 4. Optional: track location while the phone is locked (OwnTracks)
1. Install **OwnTracks** (free).
2. Go to Preferences → Connection → Mode **HTTP**.
3. Set the URL to: `https://ride-rain.<your-subdomain>.workers.dev/loc?key=YOUR_KEY`
4. Use the "Significant changes" monitoring mode for everyday use (light on battery),
   or "Move" mode during rides for frequent updates.

## Behaviour
- Alerts once per rain spell. It alerts again only after a dry check and at least 45 minutes.
- There are no alerts during quiet hours (default 22:00–06:00). Change this in ⚙.
- **Check rain now** in ⚙ forces a check and always sends a result.

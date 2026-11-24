// The service worker: it keeps every file of the app, so after the first
// visit Riff Buddy opens and runs with no connection at all. The build writes
// the file list and a version into this copy; a new build means a new version,
// which waits until the page says it's a good moment, then takes over and the
// page reloads. Until then the old version's files stay, so the page that's
// open never finds one of its own missing. Requests to other sites (the AI
// providers) pass straight through.
const VERSION = '__VERSION__';
const FILES = __FILES__;
const CACHE = 'riffbuddy-' + VERSION;

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES)));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('riffbuddy-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// The page says when the new version can take over.
self.addEventListener('message', (e) => { if (e.data === 'update') self.skipWaiting(); });

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return;
  e.respondWith(
    caches.open(CACHE)
      .then((c) => c.match(e.request, { ignoreSearch: true }).then((hit) => hit || (e.request.mode === 'navigate' ? c.match('./') : null)))
      .then((hit) => hit || fetch(e.request)),
  );
});

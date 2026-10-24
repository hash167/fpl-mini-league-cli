// FPL Live Tracker service worker, build 1.40.
// Network only: this worker never caches pages, API JSON (/live, /leagues, /fixtures, /entries)
// or event streams. It exists for installability/notifications and to purge any old caches.
const BUILD = "1.40";
self.addEventListener("install", (event) => {
  event.waitUntil(self.skipWaiting());
});
self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.map((k) => caches.delete(k)));
    await self.clients.claim();
  })());
});
// No fetch handler on purpose: every request goes straight to the network.

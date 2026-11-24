// API keys in the browser. Each is encrypted (AES-GCM) with a key the page
// can use but never read back: WebCrypto marks it non-extractable, and the
// browser keeps it in IndexedDB. The encrypted values sit in localStorage.
// Weaker than a phone's hardware key store, as Settings says: anything that
// can run script on this page can ask for them to be decrypted.
window.rbKeys = {
  key: null, prefix: 'riffbuddy.key.',

  async cryptoKey() {
    if (this.key) return this.key;
    const db = await new Promise((ok, fail) => {
      const r = indexedDB.open('riffbuddy-keys', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('k');
      r.onsuccess = () => ok(r.result);
      r.onerror = () => fail(r.error);
    });
    const get = (name) => new Promise((ok, fail) => {
      const r = db.transaction('k').objectStore('k').get(name);
      r.onsuccess = () => ok(r.result); r.onerror = () => fail(r.error);
    });
    let key = await get('main');
    if (!key) {
      key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
      await new Promise((ok, fail) => {
        const t = db.transaction('k', 'readwrite');
        t.objectStore('k').put(key, 'main');
        t.oncomplete = ok; t.onerror = () => fail(t.error);
      });
    }
    this.key = key;
    return key;
  },

  // Every stored secret, decrypted, as JSON {name: value}.
  async load() {
    const key = await this.cryptoKey();
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
      const name = localStorage.key(i);
      if (!name.startsWith(this.prefix)) continue;
      try {
        const [iv, data] = JSON.parse(localStorage.getItem(name)).map(s => Uint8Array.from(atob(s), c => c.charCodeAt(0)));
        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, data);
        out[name.slice(this.prefix.length)] = new TextDecoder().decode(plain);
      } catch (e) {
        console.warn('rbKeys: one key could not be read and was left alone');
      }
    }
    return JSON.stringify(out);
  },

  async put(name, value) {
    const key = await this.cryptoKey();
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(value)));
    const b64 = (a) => btoa(String.fromCharCode(...a));
    localStorage.setItem(this.prefix + name, JSON.stringify([b64(iv), b64(data)]));
  },

  remove(name) { localStorage.removeItem(this.prefix + name); },
};

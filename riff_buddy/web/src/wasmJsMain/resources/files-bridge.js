// The app's own files, kept in the browser's private storage (OPFS) under
// files/. The app works on a copy in memory, so its reads and writes are
// synchronous; this loads that copy on start and writes back what changed.
window.rbFiles = {
  async dir(path, create) {
    let d = await navigator.storage.getDirectory();
    for (const part of path.split('/').filter(Boolean)) d = await d.getDirectoryHandle(part, { create });
    return d;
  },

  // Every stored file, as JSON [{path, size}], paths starting with "/".
  async list() {
    const out = [];
    const walk = async (d, prefix) => {
      for await (const [name, h] of d.entries()) {
        if (h.kind === 'directory') await walk(h, prefix + '/' + name);
        else out.push({ path: prefix + '/' + name, size: (await h.getFile()).size });
      }
    };
    try { await walk(await this.dir('files', false), '/files'); } catch (e) { if (e.name !== 'NotFoundError') throw e; }
    return JSON.stringify(out);
  },

  async read(path) {
    const parts = path.split('/').filter(Boolean), name = parts.pop();
    const h = await (await this.dir(parts.join('/'), false)).getFileHandle(name);
    return new Uint8Array(await (await h.getFile()).arrayBuffer());
  },

  async write(path, bytes) {
    const parts = path.split('/').filter(Boolean), name = parts.pop();
    const h = await (await this.dir(parts.join('/'), true)).getFileHandle(name, { create: true });
    const w = await h.createWritable();
    await w.write(bytes);
    await w.close();
  },

  async remove(path) {
    const parts = path.split('/').filter(Boolean), name = parts.pop();
    try { await (await this.dir(parts.join('/'), false)).removeEntry(name); } catch (e) {}
  },

  // Asked once the app is installed, so the browser keeps the data under storage pressure.
  async persist() { return navigator.storage && navigator.storage.persist ? navigator.storage.persist() : false; },

  async fetchBytes(url) {
    const r = await fetch(url);
    if (!r.ok) throw new Error(url + ': ' + r.status);
    return new Uint8Array(await r.arrayBuffer());
  },
};

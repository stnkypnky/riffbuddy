// The page's side of the database: one SQLite database in memory, saved
// whole to the browser's private file storage (OPFS) shortly after each change
// and loaded from it on start. In memory, every call is synchronous, which is
// what the app's database code expects; the save happens afterwards.
//
// Values cross as JSON. A cell is null, a string, {i: "123"} for an integer
// (text, so 64 bits survive) or {r: 1.5} for a real, so the app can tell 2
// from 2.0 as it can on a phone.
window.rbDb = {
  sqlite3: null, db: null, file: 'riffbuddy.db', timer: null, saving: null, dirty: false,

  async open(sqlite3) {
    this.sqlite3 = sqlite3;
    this.db = new sqlite3.oo1.DB(':memory:');
    try {
      const dir = await navigator.storage.getDirectory();
      const h = await dir.getFileHandle(this.file);
      const bytes = new Uint8Array(await (await h.getFile()).arrayBuffer());
      if (bytes.length) {
        const capi = sqlite3.capi, wasm = sqlite3.wasm;
        const p = wasm.allocFromTypedArray(bytes);
        const rc = capi.sqlite3_deserialize(this.db.pointer, 'main', p, bytes.length, bytes.length,
          capi.SQLITE_DESERIALIZE_FREEONCLOSE | capi.SQLITE_DESERIALIZE_RESIZEABLE);
        if (rc) throw new Error('could not read the saved database (' + rc + ')');
      }
    } catch (e) {
      if (e.name !== 'NotFoundError') console.warn('rbDb: starting empty:', e);
    }
    this.db.exec('PRAGMA foreign_keys = ON');
    // A change can't be lost to the page closing: hidden is the last moment
    // a browser promises to run anything.
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') this.saveNow(); });
    return sqlite3.version.libVersion;
  },

  bind(json) {
    return JSON.parse(json).map(v => (v !== null && typeof v === 'object') ? ('i' in v ? BigInt(v.i) : v.r) : v);
  },

  cell(stmt, i) {
    const capi = this.sqlite3.capi;
    switch (capi.sqlite3_column_type(stmt.pointer, i)) {
      case capi.SQLITE_NULL: return null;
      case capi.SQLITE_INTEGER: return { i: String(stmt.get(i, capi.SQLITE_INTEGER)) };
      case capi.SQLITE_FLOAT: return { r: stmt.get(i) };
      default: return String(stmt.get(i));
    }
  },

  query(sql, bindJson) {
    const stmt = this.db.prepare(sql);
    try {
      const args = this.bind(bindJson);
      if (args.length) stmt.bind(args);
      const cols = stmt.getColumnNames();
      const rows = [];
      while (stmt.step()) {
        const row = new Array(cols.length);
        for (let i = 0; i < cols.length; i++) row[i] = this.cell(stmt, i);
        rows.push(row);
      }
      return JSON.stringify({ cols, rows });
    } finally {
      stmt.finalize();
    }
  },

  // One statement that changes things; answers how many rows and the last id.
  run(sql, bindJson) {
    const args = this.bind(bindJson);
    this.db.exec({ sql, bind: args.length ? args : undefined });
    const capi = this.sqlite3.capi;
    return JSON.stringify({
      changes: capi.sqlite3_changes(this.db.pointer),
      id: String(capi.sqlite3_last_insert_rowid(this.db.pointer)),
    });
  },

  exec(sql) { this.db.exec(sql); },

  // Called after anything is committed: saves a moment later, once for a burst.
  changed() {
    this.dirty = true;
    if (this.timer) return;
    this.timer = setTimeout(() => { this.timer = null; this.saveNow(); }, 400);
  },

  async saveNow() {
    if (!this.dirty) return;
    if (this.saving) { await this.saving; return this.saveNow(); }
    this.dirty = false;
    this.saving = (async () => {
      try {
        const bytes = this.sqlite3.capi.sqlite3_js_db_export(this.db);
        const dir = await navigator.storage.getDirectory();
        const h = await dir.getFileHandle(this.file, { create: true });
        // A writable commits all at once on close, so a save cut off halfway
        // leaves the last whole copy in place.
        const w = await h.createWritable();
        await w.write(bytes);
        await w.close();
      } catch (e) {
        this.dirty = true;
        console.warn('rbDb: save failed', e);
      } finally {
        this.saving = null;
      }
    })();
    await this.saving;
  },
};

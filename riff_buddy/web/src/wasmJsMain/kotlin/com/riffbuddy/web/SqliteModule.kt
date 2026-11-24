@file:JsModule("@sqlite.org/sqlite-wasm")

package com.riffbuddy.web

import kotlin.js.Promise

/** Loads SQLite's WebAssembly build; resolves to its API object. */
@JsName("default")
external fun sqlite3InitModule(): Promise<JsAny>

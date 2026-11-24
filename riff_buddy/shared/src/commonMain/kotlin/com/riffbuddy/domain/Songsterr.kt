package com.riffbuddy.domain

/**
 * Building a link to Songsterr, and nothing else.
 *
 * **The hard rule.** Riff Buddy links out and stops. It does not embed Songsterr
 * in a WebView with injected script, scrape it, automate its player, or store
 * anything it serves. Only the URL and the player's own notes are kept. Their
 * subscription applies in their own browser, where it belongs.
 *
 * URL shapes verified against the live site:
 *   search — https://www.songsterr.com/?pattern=<query>&inst=guitar
 *   a tab  — https://www.songsterr.com/a/wsa/<artist>-<song>-tab-s<id>
 *
 * A reliable search URL exists, so search is the normal path — but
 * [FALLBACK] stays, because the site can change its URLs and it is better not to
 * depend on its internals.
 */
object Songsterr {

    const val HOST = "songsterr.com"

    /**
     * The Songsterr Android app.
     *
     * Worth preferring when it is installed: the player's subscription is signed
     * in there, and the app's tab player is the thing they actually want. It claims
     * the domain as a verified app link, but a plain VIEW intent still lands in
     * a chooser or a browser unless the package is named outright.
     */
    const val ANDROID_PACKAGE = "com.songsterr"
    const val FALLBACK = "https://www.songsterr.com/"

    /** Search Songsterr for a song, narrowed to guitar tabs. */
    fun searchUrl(title: String, artist: String): String {
        val query = listOf(artist, title)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isEmpty()) return FALLBACK
        return "https://www.songsterr.com/?pattern=${encode(query)}&inst=guitar"
    }

    /**
     * Checks a pasted URL before it is saved to a song.
     *
     * Two things are being prevented. A typo silently saving a dead link is the
     * mild one. The real one is that this URL is later handed to the system
     * browser: anything that is not an https Songsterr address has no business
     * being launched from a button labeled "Open in Songsterr", and a
     * `javascript:` or `intent:` scheme pasted in by accident should never get
     * that far.
     */
    fun validateSavedUrl(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Paste a Songsterr link first."))

        // Characters no web address may hold, which a pasted lookalike might use
        // to make one site's name read as another's.
        val scheme = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""").find(trimmed)?.groupValues?.get(1)
        if (scheme == null || trimmed.any { it.isWhitespace() || it in "\"<>\\^`{|}" }) {
            return Result.failure(IllegalArgumentException("That does not look like a web address."))
        }

        if (!scheme.equals("https", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Links must start with https://"))
        }

        // The site is what follows "//", less any "name@" in front and ":port" behind.
        val host = Regex("""^https://([^/?#]*)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
            ?.substringAfterLast('@')?.substringBefore(':')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalArgumentException("That address has no site name in it."))

        // Exact host or a subdomain of it. A plain `endsWith` would happily
        // accept "notsongsterr.com" and, worse, "songsterr.com.example.net".
        if (host != HOST && !host.endsWith(".$HOST")) {
            return Result.failure(IllegalArgumentException("That is not a Songsterr link."))
        }

        return Result.success(trimmed)
    }

    /** What the button opens: the saved tab if there is one, otherwise a search. */
    fun openUrl(savedUrl: String?, title: String, artist: String): String {
        val saved = savedUrl?.takeIf { it.isNotBlank() }
        if (saved != null && validateSavedUrl(saved).isSuccess) return saved
        return searchUrl(title, artist)
    }

    /** Percent-encoded as a form value is, but with spaces as %20. */
    private fun encode(value: String): String = formEncode(value).replace("+", "%20")
}

package com.riffbuddy.ai

/**
 * Whether a request has any chance of reaching a provider, asked once per
 * request. Without it, a request with no connection would spend a usage count,
 * time out, spend another on the fallback and only then report a network
 * error — instead of offering a cached answer or a clear message straight away.
 */
fun interface OnlineCheck : () -> Boolean

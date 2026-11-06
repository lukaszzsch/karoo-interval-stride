package com.daviddokakis.karoointerval

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.IOException

data class HttpResult(val statusCode: Int, val body: String)

/** No Wi-Fi and no connected phone (or the request couldn't reach the server). */
class NoConnectionException(message: String) : IOException(message)

/**
 * GET through the Karoo's own network: Wi-Fi when connected, else Bluetooth to the phone's
 * Companion app. Doesn't wait for a connection, so being offline fails fast.
 */
suspend fun KarooSystemService.karooHttpGet(
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long = 45_000,
): HttpResult {
    val complete = withTimeout(timeoutMs) {
        callbackFlow {
            val id = addConsumer(
                OnHttpResponse.MakeHttpRequest("GET", url, headers, waitForConnection = false),
                onError = { msg -> close(NoConnectionException(msg)) },
            ) { event: OnHttpResponse -> trySend(event.state) }
            awaitClose { removeConsumer(id) }
        }.filterIsInstance<HttpResponseState.Complete>().first()
    }
    val error = complete.error
    if (error != null && complete.statusCode !in 200..599) throw NoConnectionException(error)
    return HttpResult(complete.statusCode, complete.body?.decodeToString().orEmpty())
}

/**
 * Any request through the Karoo's network. With [waitForConnection] the Karoo holds the request
 * until Wi-Fi or the phone is back (used for the after-ride rating sync).
 */
suspend fun KarooSystemService.karooHttp(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray? = null,
    waitForConnection: Boolean = true,
): HttpResult {
    val complete = callbackFlow {
        val id = addConsumer(
            OnHttpResponse.MakeHttpRequest(method, url, headers, body, waitForConnection = waitForConnection),
            onError = { msg -> close(NoConnectionException(msg)) },
        ) { event: OnHttpResponse -> trySend(event.state) }
        awaitClose { removeConsumer(id) }
    }.filterIsInstance<HttpResponseState.Complete>().first()
    val error = complete.error
    if (error != null && complete.statusCode !in 200..599) throw NoConnectionException(error)
    return HttpResult(complete.statusCode, complete.body?.decodeToString().orEmpty())
}

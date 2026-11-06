package com.daviddokakis.karoointerval

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// Flow helpers adapted from the karoo-ext sample app (Apache License 2.0).

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val id = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySendBlocking(event.state)
    }
    awaitClose { removeConsumer(id) }
}

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val id = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(id) }
}

fun StreamState.field(name: String): Double? =
    (this as? StreamState.Streaming)?.dataPoint?.values?.get(name)

fun StreamState.single(): Double? =
    (this as? StreamState.Streaming)?.dataPoint?.singleValue

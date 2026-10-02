package com.riffbuddy.desktop.tabs

import alphaTab.IEventEmitter
import alphaTab.IEventEmitterOfT
import java.util.concurrent.CopyOnWriteArrayList

/** An event alphaTab can listen to. Its own implementation is internal to it. */
class Emitter : IEventEmitter {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    override fun on(value: () -> Unit): () -> Unit {
        listeners += value
        return { listeners -= value }
    }
    override fun off(value: () -> Unit) {
        listeners -= value
    }
    fun trigger() = listeners.forEach { it() }
}

/** An event with a value alphaTab can listen to. */
class EmitterOf<T> : IEventEmitterOfT<T> {
    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()
    override fun on(value: (T) -> Unit): () -> Unit {
        listeners += value
        return { listeners -= value }
    }
    override fun off(value: (T) -> Unit) {
        listeners -= value
    }
    fun trigger(value: T) = listeners.forEach { it(value) }
}

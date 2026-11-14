package com.example.careway.core

/** Atomic snapshots shared between SDK workers and the main-thread controller. No UI work here. */
class StateCell<T>(initial: T) {
    @Volatile var value: T = initial
    @Synchronized fun update(change: (T) -> T) { value = change(value) }
}

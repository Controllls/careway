package com.example.careway

import com.example.careway.core.StateCell
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StateCellTest {
    @Test fun concurrentSdkUpdatesDoNotLoseChanges() {
        val state=StateCell(0)
        val workers=Executors.newFixedThreadPool(4)
        val start=CountDownLatch(1)
        try {
            val jobs=(1..4).map { workers.submit { start.await(); repeat(5000) { state.update { it+1 } } } }
            start.countDown()
            jobs.forEach { it.get(5,TimeUnit.SECONDS) }
            assertEquals(20000,state.value)
        } finally { workers.shutdownNow() }
    }
}

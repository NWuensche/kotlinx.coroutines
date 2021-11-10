package kotlinx.coroutines

import kotlinx.coroutines.channels.*
import kotlin.test.*

/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

abstract class AbstractDispatcherConcurrencyTest : TestBase() {

   public abstract val dispatcher: CoroutineDispatcher

    @Test
    fun testLaunchAndJoin() = runMtTest {
        expect(1)
        var capturedMutableState = 0
        val job = GlobalScope.launch(dispatcher) {
            ++capturedMutableState
            expect(2)
        }
        runBlocking { job.join() }
        assertEquals(1, capturedMutableState)
        finish(3)
    }

    @Test
    fun testDispatcherIsActuallyMultithreaded() = runMtTest {
        val channel = Channel<Int>()
        GlobalScope.launch(dispatcher) {
            channel.send(42)
        }

        var result = ChannelResult.failure<Int>()
        while (!result.isSuccess) {
            result = channel.tryReceive()
            // Block the thread, wait
        }
        // Delivery was successful, let's check it
        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun testDelayInDispatcher() = runMtTest {
        expect(1)
        val job = GlobalScope.launch(dispatcher) {
            expect(2)
            delay(100)
            expect(3)
        }
        runBlocking { job.join() }
        finish(4)
    }
}

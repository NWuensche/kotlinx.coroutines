/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.exceptions.*
import kotlin.test.*

class SemaphoreStressTest : TestBase() {

    @Test
    fun testStressTestAsMutex() = runMtTest {
        val n = 1_000 * stressTestMultiplier / stressTestNativeDivisor
        val k = 100
        var shared = 0
        val semaphore = Semaphore(1)
        val jobs = List(n) {
            launch(Dispatchers.Default) {
                repeat(k) {
                    semaphore.acquire()
                    shared++
                    semaphore.release()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(n * k, shared)
    }

    @Test
    fun testStress() = runMtTest {
        val n = 10_000 * stressTestMultiplier / stressTestNativeDivisor
        val k = 100
        val semaphore = Semaphore(10)
        val jobs = List(n) {
            launch(Dispatchers.Default) {
                repeat(k) {
                    semaphore.acquire()
                    semaphore.release()
                }
            }
        }
        jobs.forEach { it.join() }
    }

    @Test
    fun testNativeSpecificStress() = runMtTest {
        /*
         * Dispatchers.Default is still single-threaded, so here we're doing actual concurrency
         */
        val n = 100_000 * stressTestMultiplier / stressTestNativeDivisor
        var shared = 0
        val semaphore = Semaphore(1)
        val job = launch(Dispatchers.Default) {
            repeat(n) {
                semaphore.acquire()
                shared++
                semaphore.release()
            }
        }

        repeat(n) {
            semaphore.acquire()
            shared++
            semaphore.release()
        }

        job.join()
        assertEquals(n * 2, shared)
    }

    @Test
    fun testStressCancellation() = runMtTest {
        val n = 10_000 * stressTestMultiplier / stressTestNativeDivisor
        val semaphore = Semaphore(1)
        semaphore.acquire()
        repeat(n) {
            val job = launch(Dispatchers.Default) {
                semaphore.acquire()
            }
            yield()
            job.cancelAndJoin()
        }
        assertEquals(0, semaphore.availablePermits)
        semaphore.release()
        assertEquals(1, semaphore.availablePermits)
    }

    /**
     * This checks if repeated releases that race with cancellations put
     * the semaphore into an incorrect state where permits are leaked.
     */
    @Test
    fun testStressReleaseCancelRace() = runMtTest {
        val n = 10_000 * stressTestMultiplier / stressTestNativeDivisor
        val semaphore = Semaphore(1, 1)
        newSingleThreadContext("SemaphoreStressTest").use { pool ->
            repeat (n) {
                // Initially, we hold the permit and no one else can `acquire`,
                // otherwise it's a bug.
                assertEquals(0, semaphore.availablePermits)
                var job1EnteredCriticalSection = false
                val job1 = launch(start = CoroutineStart.UNDISPATCHED) {
                    semaphore.acquire()
                    job1EnteredCriticalSection = true
                    semaphore.release()
                }
                // check that `job1` didn't finish the call to `acquire()`
                assertEquals(false, job1EnteredCriticalSection)
                val job2 = launch(pool) {
                    semaphore.release()
                }
                // Because `job2` executes in a separate thread, this
                // cancellation races with the call to `release()`.
                job1.cancelAndJoin()
                job2.join()
                assertEquals(1, semaphore.availablePermits)
                semaphore.acquire()
            }
        }
    }

    @Test
    fun testShouldBeUnlockedOnCancellation() = runMtTest {
        val semaphore = Semaphore(1)
        val n = 1000 * stressTestMultiplier
        repeat(n) {
            val job = launch(Dispatchers.Default) {
                semaphore.acquire()
                semaphore.release()
            }
            semaphore.withPermit {
                job.cancel()
            }
            job.join()
            assertTrue { semaphore.availablePermits == 1 }
        }
    }
}

/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
package kotlinx.coroutines.rateLimiter

import kotlinx.atomicfu.*
import kotlin.time.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import kotlinx.coroutines.*

private const val NANOS_PER_MILLI = 1_000_000L

@ExperimentalTime
public interface RateLimiter {
    public fun addTokens(tokens: Long)
    public fun removeTokens(tokens: Long): Unit = addTokens(-tokens)
    public fun tryConsume(tokens: Long = 1): Boolean
    public suspend fun consume(tokens: Long = 1, maxDelay: Duration = Duration.INFINITE)
    public fun nextAvailableTime(tokens: Long): TimeMark?
}

@ExperimentalTime
internal abstract class RateLimiterImplBase: RateLimiter
{
    abstract fun requestTimeSlot(atTime: Long, tokens: Long, maxDelay: Long): Long
    abstract fun relinquishTokens(atTime: Long, reservationTime: Long, tokens: Long)
    abstract fun addTokens(atTime: Long, tokens: Long)
    abstract fun nextAvailableTime(atTime: Long, tokens: Long): Long

    abstract val timeMark: TimeMark

    fun timeSinceInitialization(): Long = timeMark.elapsedNow().toLongNanoseconds()

    private suspend fun consume(tokens: Long, maxDelay: Long) {
        require(tokens > 0)
        val currentTime = timeSinceInitialization()
        val nextSlot = requestTimeSlot(currentTime, tokens, maxDelay)
        if (nextSlot == Long.MAX_VALUE) {
            throw TimeoutCancellationException("Could not allocate $tokens tokens to be available in $maxDelay")
        }
        val toSleep = nextSlot - currentTime
        try {
            delay((toSleep + NANOS_PER_MILLI / 2) / NANOS_PER_MILLI)
        } catch (e: CancellationException) {
            relinquishTokens(timeSinceInitialization(), nextSlot, tokens)
        }
    }

    override fun addTokens(tokens: Long): Unit = addTokens(timeSinceInitialization(), tokens)

    override fun tryConsume(tokens: Long): Boolean {
        require(tokens > 0)
        return requestTimeSlot(timeSinceInitialization(), tokens, 0) != Long.MAX_VALUE
    }

    override suspend fun consume(tokens: Long, maxDelay: Duration): Unit =
        consume(tokens, maxDelay.toLongNanoseconds())

    override fun nextAvailableTime(tokens: Long): TimeMark? {
        val currentTime = timeSinceInitialization()
        val nextSlot = nextAvailableTime(currentTime, tokens)
        return if (nextSlot == Long.MAX_VALUE) {
            null
        } else {
            timeMark + nextSlot.nanoseconds
        }
    }
}

@ExperimentalTime
internal class RateLimitedFlow<T>(
    val flow: Flow<T>, private val rateLimiter: RateLimiter, private val tokensPerValue: (T) -> Long): Flow<T> {
    @InternalCoroutinesApi
    override suspend fun collect(collector: FlowCollector<T>) {
        flow.collect {
            rateLimiter.consume(tokensPerValue(it))
            collector.emit(it)
        }
    }
}

@ExperimentalTime
public fun <T> Flow<T>.rateLimit(rateLimiter: RateLimiter, tokensPerValue: (T) -> Long): Flow<T> =
    RateLimitedFlow(this, rateLimiter, tokensPerValue)

internal data class Bandwidth(
    val capacity: Long, val refillPeriod: Long, val quantum: Long = 1, val initialAmount: Long = capacity) {
    init {
        require(capacity > 0)
        require(refillPeriod > 0)
        require(quantum > 0)
        require(initialAmount >= 0)
    }

    internal fun timeToCreateTokens(tokens: Long): Long {
        val periods = run { // rounding up deficit / bandwidth.quantum
            val a = tokens / quantum
            if (a * quantum != tokens) { a + 1 } else { a }
        }
        return periods * refillPeriod
    }
}

@ExperimentalTime
internal class TokenBucketRateLimiter(bandwidths: Array<Bandwidth>,
                                      timeSource: kotlin.time.TimeSource = kotlin.time.TimeSource.Monotonic):
    RateLimiterImplBase()
{
    override val timeMark = timeSource.markNow()
    private val initTime = timeMark.elapsedNow().toLongNanoseconds()

    internal class State(private val bandwidths: Array<Bandwidth>,
                         private val tokensPerBandwidth: Array<Long>,
                         private val lastUpdateTimes: Array<Long>,
                         private var lastEventTime: Long)
    {
        init { require(bandwidths.isNotEmpty()) }

        fun copyOf(): State = State(bandwidths, tokensPerBandwidth.copyOf(), lastUpdateTimes.copyOf(), lastEventTime)

        val availableTokens get() = tokensPerBandwidth.reduce { a, b -> min(a, b) }

        fun registerTokenAcquisition(atTime: Long, tokens: Long) {
            consume(tokens)
            if (atTime > lastEventTime) {
                lastEventTime = atTime
            }
        }

        // Inspired by https://github.com/golang/time
        private fun unregisterTokenAcquisition(atTime: Long, reservationTime: Long, tokens: Long) {
            if (reservationTime == lastEventTime) {
                val previousEvent = lastEventTime - bandwidths.map { b -> b.timeToCreateTokens(tokens) }.min()!!
                if (previousEvent > atTime) {
                    lastEventTime = previousEvent
                }
            }
        }

        fun refill(atTime: Long) {
            for (i in bandwidths.indices) {
                val bandwidth = bandwidths[i]
                val previousUpdate = lastUpdateTimes[i]
                if (atTime <= previousUpdate) {
                    continue
                }
                val completePeriods = (atTime - previousUpdate) / bandwidth.refillPeriod
                lastUpdateTimes[i] = previousUpdate + completePeriods * bandwidth.refillPeriod // <= atTime, no overflow
                val currentTokens = tokensPerBandwidth[i] + completePeriods * bandwidth.quantum // TODO: check overflow
                tokensPerBandwidth[i] = if (currentTokens > bandwidth.capacity) {
                    bandwidth.capacity
                } else {
                    currentTokens
                }
            }
        }

        fun addTokens(tokens: Long) {
            for (i in bandwidths.indices) {
                val capacity = bandwidths[i].capacity
                val newTokens = tokensPerBandwidth[i] + tokens // TODO: check overflow
                tokensPerBandwidth[i] = if (newTokens >= capacity) capacity else newTokens
            }
        }

        fun consume(tokens: Long) = addTokens(-tokens)

        fun nextDeficitCompensationTime(tokens: Long): Long = bandwidths.indices
            .map { i ->
                val currentTokens = tokensPerBandwidth[i]
                val timeToWaitSinceLastUpdate = if (currentTokens >= tokens) {
                    0L
                } else {
                    val deficit = tokens - currentTokens
                    if (deficit <= 0) {
                        // an overflow occurred: the existing debt is too large.
                        return@map Long.MAX_VALUE
                    }
                    bandwidths[i].timeToCreateTokens(deficit)
                }
                lastUpdateTimes[i] + timeToWaitSinceLastUpdate
            }
            .reduce { a, b -> max(a, b) }

        fun returnTokens(atTime: Long, reservationTime: Long, tokens: Long): Boolean {
            val tokensAlreadyCreated = bandwidths.indices
                .map { i ->
                    val bandwidth = bandwidths[i]
                    val completePeriods = (lastEventTime - reservationTime) / bandwidth.refillPeriod
                    completePeriods * bandwidth.quantum // TODO: check overflow
                }
                .reduce { a, b -> max(a, b) }
            val tokensToRestore = tokens - tokensAlreadyCreated
            if (tokensToRestore > 0) {
                addTokens(tokensToRestore)
                unregisterTokenAcquisition(atTime, reservationTime, tokens)
                return true
            }
            return false
        }
    }

    val state: AtomicRef<State> = atomic(State(
        bandwidths,
        Array(bandwidths.size) { bandwidths[it].initialAmount },
        Array(bandwidths.size) { initTime },
        Long.MIN_VALUE))

    private inline fun <T> withState(block: (State) -> T): T {
        while (true) {
            val oldState = state.value
            val currentState = oldState.copyOf()
            val result = block(currentState) // may modify `currentState`
            if (state.compareAndSet(oldState, currentState)) {
                return result
            }
        }
    }

    override fun requestTimeSlot(atTime: Long, tokens: Long, maxDelay: Long): Long = withState { currentState ->
        require(tokens > 0)
        currentState.refill(atTime)
        val deficitCompensationTime = currentState.nextDeficitCompensationTime(tokens)
        if (deficitCompensationTime == Long.MAX_VALUE) {
            return Long.MAX_VALUE
        }
        if (deficitCompensationTime - atTime > maxDelay) {
            return Long.MAX_VALUE
        }
        currentState.registerTokenAcquisition(deficitCompensationTime, tokens)
        deficitCompensationTime
    }

    override fun addTokens(atTime: Long, tokens: Long) = withState { currentState ->
        currentState.refill(atTime)
        currentState.addTokens(tokens)
    }

    override fun nextAvailableTime(atTime: Long, tokens: Long): Long {
        val currentState = state.value.copyOf()
        currentState.refill(atTime)
        return currentState.nextDeficitCompensationTime(tokens)
    }

    override fun relinquishTokens(atTime: Long, reservationTime: Long, tokens: Long) = withState { currentState ->
        require(tokens > 0)
        currentState.refill(atTime)
        if (!currentState.returnTokens(atTime, reservationTime, tokens)) {
            return // do not loop even if CAS fails
        }
    }
}

@ExperimentalTime
public fun rateLimiter(@BuilderInference block: RateLimiterBuilder.() -> Unit): RateLimiter {
    val builder = RateLimiterBuilder()
    builder.block()
    return builder.build()
}

@ExperimentalTime
public class RateLimiterBuilder {

    private val bandwidths = mutableListOf<Bandwidth>()

    public fun smooth(capacity: Long, refillPeriod: Long) {
        bandwidths.add(Bandwidth(capacity, refillPeriod / capacity))
    }

    @ExperimentalTime
    public fun smooth(capacity: Long, refillPeriod: Duration) {
        smooth(capacity, refillPeriod.toLongNanoseconds())
    }

    public fun periodic(capacity: Long, refillPeriod: Long, quantum: Long = capacity, initialAmount: Long = capacity) {
        bandwidths.add(Bandwidth(capacity, refillPeriod, quantum, initialAmount))
    }

    @ExperimentalTime
    public fun periodic(capacity: Long, refillPeriod: Duration, quantum: Long = capacity, initialAmount: Long = capacity) {
        periodic(capacity, refillPeriod.toLongNanoseconds(), quantum, initialAmount)
    }

    internal fun build(): RateLimiter = TokenBucketRateLimiter(bandwidths.toTypedArray())
}
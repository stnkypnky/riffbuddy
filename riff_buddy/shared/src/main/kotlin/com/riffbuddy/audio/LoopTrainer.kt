package com.riffbuddy.audio

import com.riffbuddy.domain.SessionItem
import kotlin.math.roundToInt

/**
 * The loop trainer: start slow, and every few clean passes of the loop,
 * go a little faster, until the target speed has been *played*.
 *
 * Deliberately not the metronome's SpeedTrainer. That one's "stop at target"
 * stops the moment the target is reached; for a loop that means the fastest
 * speed is never actually heard. Here the target gets its full count of loops
 * like every step before it, and only then does the trainer finish.
 *
 * Pure state, like SpeedTrainer, so "runs unattended and stops at target" is
 * a unit test rather than a stopwatch.
 */
data class LoopTrainerConfig(
    val startPercent: Int = 60,
    val targetPercent: Int = 100,
    val stepPercent: Int = 5,
    val everyLoops: Int = 3,
    /** Step only on "Clean run", never on a loop count. */
    val manual: Boolean = false,
) {
    init {
        require(startPercent in 25..150 && targetPercent in 25..150) { "speeds must be 25-150%" }
        require(startPercent < targetPercent) { "the target must be faster than the start" }
        require(stepPercent in 1..50) { "step must be 1-50%" }
        require(everyLoops in 1..50) { "every must be 1-50 loops" }
    }
}

data class LoopTrainerState(
    val config: LoopTrainerConfig,
    val percent: Int,
    val loopsAtThisSpeed: Int = 0,
    val totalLoops: Int = 0,
    val done: Boolean = false,
) {
    val atTarget: Boolean get() = percent == config.targetPercent

    val progress: Float
        get() = ((percent - config.startPercent).toFloat() / (config.targetPercent - config.startPercent)).coerceIn(0f, 1f)
}

object LoopTrainer {

    fun start(config: LoopTrainerConfig) = LoopTrainerState(config, percent = config.startPercent)

    /** The loop came round once. In manual mode it is counted but never steps. */
    fun onLoop(state: LoopTrainerState): LoopTrainerState {
        if (state.done) return state
        val counted = state.copy(loopsAtThisSpeed = state.loopsAtThisSpeed + 1, totalLoops = state.totalLoops + 1)
        if (state.config.manual || counted.loopsAtThisSpeed < state.config.everyLoops) return counted
        return step(counted)
    }

    /** "Clean run": the player says this speed is done. */
    fun onCleanRun(state: LoopTrainerState): LoopTrainerState = if (state.done) state else step(state)

    private fun step(state: LoopTrainerState): LoopTrainerState {
        if (state.atTarget) return state.copy(done = true)
        // Clamped, so 60 -> 98 in fives ends on 98, not a 100 never asked for.
        val next = minOf(state.percent + state.config.stepPercent, state.config.targetPercent)
        return state.copy(percent = next, loopsAtThisSpeed = 0)
    }

    /**
     * The run as a session-log item, written in the log's own terms — BPM
     * when the recording's tempo is known, since that is what the progress
     * lines are drawn from; percentages otherwise.
     */
    fun logItem(
        loopName: String,
        songId: Long?,
        seconds: Int,
        startPercent: Int,
        endPercent: Int,
        loops: Int,
        songBpm: Int?,
    ): SessionItem = SessionItem(
        songId = songId,
        exerciseName = "Loop: $loopName",
        seconds = seconds,
        bpmStart = songBpm?.let { (it * startPercent / 100.0).roundToInt() },
        bpmEnd = songBpm?.let { (it * endPercent / 100.0).roundToInt() },
        notes = "$startPercent% → $endPercent%, $loops loops",
    )
}

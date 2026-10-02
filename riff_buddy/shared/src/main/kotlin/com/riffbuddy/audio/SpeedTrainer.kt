package com.riffbuddy.audio

/**
 * The speed trainer, as pure state so it can be tested without sound.
 *
 * Two modes, and the difference is only what advances a step: bars played, or
 * the player saying they nailed it.
 */
enum class TrainerMode { AUTO_RAMP, MANUAL_CONFIRM }

/** What happens on reaching the target. */
enum class AtTarget { HOLD, STOP }

data class TrainerConfig(
    val mode: TrainerMode = TrainerMode.AUTO_RAMP,
    val startBpm: Int = 60,
    val targetBpm: Int = 120,
    val step: Int = 5,
    /** AUTO_RAMP only: bars at each tempo before stepping up. */
    val everyBars: Int = 4,
    val atTarget: AtTarget = AtTarget.HOLD,
) {
    init {
        require(startBpm in MetronomeSettings.BPM_RANGE) { "startBpm out of range" }
        require(targetBpm in MetronomeSettings.BPM_RANGE) { "targetBpm out of range" }
        require(step in 1..20) { "step must be 1-20, was $step" }
        require(everyBars >= 1) { "everyBars must be at least 1" }
    }
}

data class TrainerState(
    val config: TrainerConfig,
    val currentBpm: Int,
    val barsAtThisTempo: Int = 0,
    val reachedTarget: Boolean = false,
    val shouldStop: Boolean = false,
) {
    /** 0.0 at the start tempo, 1.0 at the target. */
    val progress: Float
        get() {
            val span = config.targetBpm - config.startBpm
            if (span == 0) return 1f
            return ((currentBpm - config.startBpm).toFloat() / span).coerceIn(0f, 1f)
        }
}

object SpeedTrainer {

    fun start(config: TrainerConfig) = TrainerState(config, currentBpm = config.startBpm)

    /**
     * A bar finished.
     *
     * Only AUTO_RAMP counts bars; in MANUAL_CONFIRM the bar count is still kept
     * so the UI can show how long they have been sitting at a tempo, but it never
     * triggers a step on its own.
     */
    fun onBarComplete(state: TrainerState): TrainerState {
        if (state.reachedTarget && state.config.atTarget == AtTarget.STOP) return state
        val bars = state.barsAtThisTempo + 1
        if (state.config.mode != TrainerMode.AUTO_RAMP) return state.copy(barsAtThisTempo = bars)
        if (bars < state.config.everyBars) return state.copy(barsAtThisTempo = bars)
        return stepUp(state)
    }

    /** MANUAL_CONFIRM: "Nailed it". */
    fun onNailedIt(state: TrainerState): TrainerState = stepUp(state)

    /** MANUAL_CONFIRM: "Repeat" — same tempo, bar count restarts. */
    fun onRepeat(state: TrainerState): TrainerState = state.copy(barsAtThisTempo = 0)

    /**
     * Step toward the target, never past it.
     *
     * Clamping matters: a ramp of 60 to 90 in steps of 4 would otherwise
     * overshoot to 92 and report a target reached that was never played.
     */
    private fun stepUp(state: TrainerState): TrainerState {
        val c = state.config
        if (state.reachedTarget) {
            return if (c.atTarget == AtTarget.STOP) state.copy(shouldStop = true)
            else state.copy(barsAtThisTempo = 0)
        }
        val ascending = c.targetBpm >= c.startBpm
        val raw = if (ascending) state.currentBpm + c.step else state.currentBpm - c.step
        val next = if (ascending) minOf(raw, c.targetBpm) else maxOf(raw, c.targetBpm)
        val hit = next == c.targetBpm
        return state.copy(
            currentBpm = next,
            barsAtThisTempo = 0,
            reachedTarget = hit,
            shouldStop = hit && c.atTarget == AtTarget.STOP,
        )
    }
}

/**
 * One speed-trainer run, as it goes into the log: where it started,
 * the fastest it reached, and for how long.
 *
 * "Reached" is the highest tempo played, not the one it ended on — a run
 * stopped after a "Repeat" still reached what it reached.
 */
data class TrainerRun(
    val startBpm: Int,
    val reachedBpm: Int,
    val startedAt: java.time.Instant,
    val songId: Long? = null,
) {
    fun seen(bpm: Int): TrainerRun = if (bpm > reachedBpm) copy(reachedBpm = bpm) else this

    fun seconds(endedAt: java.time.Instant): Int =
        java.time.Duration.between(startedAt, endedAt).seconds.coerceAtLeast(0).toInt()

    /** Too short to be practice: a start pressed and stopped again. */
    fun worthOffering(endedAt: java.time.Instant): Boolean = seconds(endedAt) >= MIN_SECONDS

    fun toItem(endedAt: java.time.Instant) = com.riffbuddy.domain.SessionItem(
        songId = songId,
        exerciseName = "Speed trainer",
        seconds = seconds(endedAt),
        bpmStart = startBpm,
        bpmEnd = reachedBpm,
    )

    companion object {
        const val MIN_SECONDS = 15
    }
}


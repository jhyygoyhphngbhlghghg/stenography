package team.mikcoders.neurosteno.pipeline

/**
 * Run ASR and LLM as exclusive memory phases: release ASR weights/arenas
 * before the LLM loads, so peak native RAM is max(ASR, LLM), not the sum.
 * Release also runs when the ASR phase fails or is cancelled (the session
 * invalidates its own model on failure; eviction is idempotent and
 * non-cancellable), and never runs while the ASR transaction still owns the
 * session — callers pass an eviction that acquires the session lock.
 */
internal suspend fun <Raw, Out> runExclusivePhases(
    asrPhase: suspend () -> Raw,
    releaseAsr: suspend () -> Unit,
    llmPhase: suspend (Raw) -> Out,
): Out {
    val raw = try {
        asrPhase()
    } finally {
        releaseAsr()
    }
    return llmPhase(raw)
}

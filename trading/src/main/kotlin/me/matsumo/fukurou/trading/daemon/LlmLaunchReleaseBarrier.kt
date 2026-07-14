package me.matsumo.fukurou.trading.daemon

/**
 * Issue #154 の受理まで pre-filter activation を抑止する code-owned release barrier。
 *
 * runtime config や DB の値より優先される。true へ変更できるのは Issue #154 の
 * activation change だけである。
 */
object LlmLaunchReleaseBarrier {
    /** pre-filter activation が release 済みか。 */
    const val PREFILTER_ACTIVATION_RELEASED: Boolean = false

    /** configured value と release state の両方が有効な場合だけ pre-filter を許可する。 */
    fun isPreFilterAllowed(configured: Boolean): Boolean {
        return PREFILTER_ACTIVATION_RELEASED && configured
    }
}

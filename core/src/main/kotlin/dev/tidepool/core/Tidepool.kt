package dev.tidepool.core

/** Facts about Tidepool itself, shared by every module. */
object Tidepool {
    /** Prefix for the labels and annotations Tidepool puts on the Kubernetes objects it owns. */
    const val LABEL_PREFIX = "tidepool.dev/"

    /** Builds a Tidepool-owned label or annotation key, e.g. `tidepool.dev/owner`. */
    fun key(name: String): String {
        require(name.isNotBlank()) { "label name must not be blank" }
        return LABEL_PREFIX + name
    }
}

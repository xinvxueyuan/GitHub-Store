package zed.rainxch.core.domain.network

import zed.rainxch.core.domain.model.ProxyConfig

/**
 * Verifies that a [ProxyConfig] can actually reach the GitHub API. Implementations
 * should issue a single lightweight request through a throwaway HTTP client built
 * with the supplied config so the test exercises the same engine code path the
 * real client uses.
 */
interface ProxyTester {
    suspend fun test(config: ProxyConfig): ProxyTestOutcome
}

sealed interface ProxyTestOutcome {
    /** Connection succeeded. [latencyMs] is the round-trip time of the test request. */
    data class Success(
        val latencyMs: Long,
    ) : ProxyTestOutcome

    sealed interface Failure : ProxyTestOutcome {
        /** Could not resolve a hostname (DNS failure or unresolved proxy host). */
        data object DnsFailure : Failure

        /** Reached the network but could not connect to the proxy itself. */
        data object ProxyUnreachable : Failure

        /** Connection or socket timed out. */
        data object Timeout : Failure

        /** Proxy returned 407 / requested authentication. */
        data object ProxyAuthRequired : Failure

        /** Proxy or upstream returned a non-2xx HTTP status. */
        data class UnexpectedResponse(
            val statusCode: Int,
        ) : Failure

        /** Anything else (TLS errors, malformed config, etc.). */
        data class Unknown(
            val message: String?,
        ) : Failure
    }
}

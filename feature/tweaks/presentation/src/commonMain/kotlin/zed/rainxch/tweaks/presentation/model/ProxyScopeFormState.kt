package zed.rainxch.tweaks.presentation.model

/**
 * Per-scope form backing state for the proxy section. Each
 * [zed.rainxch.core.domain.model.ProxyScope] card owns one of these
 * — keeps the in-progress, test, and visibility flags independent
 * across scopes so the user can edit one card while a test runs on
 * another.
 */
data class ProxyScopeFormState(
    val type: ProxyType = ProxyType.SYSTEM,
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isTestInProgress: Boolean = false,
)

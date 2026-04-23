package zed.rainxch.githubstore

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Workaround for a Compose Multiplatform 1.10.x NPE on macOS where the native
 * AX bridge (`sun.lwawt.macosx.CAccessible$AXChangeNotifier`) queries a
 * Compose semantic node that has already been removed by Compose's own
 * accessibility sync loop. The stack trace fingerprint is:
 *
 *     androidx.compose.ui.platform.a11y.SemanticsOwnerAccessibility.accessibleParentOf
 *        -> sun.lwawt.macosx.CAccessible$AXChangeNotifier.propertyChange
 *
 * The uncaught exception poisons the AWT EventDispatchThread and the app
 * appears to freeze/crash on click. Installing a filtering [EventQueue]
 * swallows only that specific NPE so the EDT keeps draining events.
 * Trade-off: macOS VoiceOver may miss updates on those removed nodes.
 * Remove once the upstream fix lands (track against Compose MP 1.11+).
 *
 * See [GitHub-Store#330](https://github.com/OpenHub-Store/GitHub-Store/issues/330).
 */
object A11yCrashGuard {
    fun install() {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!osName.contains("mac")) return
        Toolkit.getDefaultToolkit().systemEventQueue.push(FilteringEventQueue())
    }

    private class FilteringEventQueue : EventQueue() {
        private val warned = AtomicBoolean(false)

        override fun dispatchEvent(event: AWTEvent) {
            try {
                super.dispatchEvent(event)
            } catch (npe: NullPointerException) {
                if (isComposeA11yNpe(npe)) {
                    if (warned.compareAndSet(false, true)) {
                        System.err.println(
                            "[A11yCrashGuard] Suppressed Compose a11y NPE on macOS " +
                                "(known issue, see GitHub-Store#330). Further occurrences silenced.",
                        )
                    }
                    return
                }
                throw npe
            }
        }

        private fun isComposeA11yNpe(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                if (current.stackTrace.any { frame ->
                        frame.className.startsWith("androidx.compose.ui.platform.a11y")
                    }
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}

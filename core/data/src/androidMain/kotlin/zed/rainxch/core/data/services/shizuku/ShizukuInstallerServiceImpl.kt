package zed.rainxch.core.data.services.shizuku

import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService implementation that runs in a privileged process (shell/root).
 * Provides silent package install/uninstall via `pm` shell commands.
 *
 * This class runs in Shizuku's process, NOT in the app's process.
 * It has shell-level (UID 2000) or root-level (UID 0) privileges.
 *
 * Uses `pm install` with stdin pipe for install, `pm uninstall` for uninstall.
 * This is the most reliable approach — avoids fragile reflection on hidden
 * IPackageInstaller/IPackageInstallerSession/IIntentSender APIs.
 *
 * MUST have a default no-arg constructor for Shizuku's UserService framework.
 */
class ShizukuInstallerServiceImpl() : IShizukuInstallerService.Stub() {

    companion object {
        private const val TAG = "ShizukuService"

        private const val STATUS_SUCCESS = 0
        private const val STATUS_FAILURE = -1

        private fun log(msg: String) = android.util.Log.d(TAG, msg)
        private fun logW(msg: String) = android.util.Log.w(TAG, msg)
        private fun logE(msg: String, e: Throwable? = null) = android.util.Log.e(TAG, msg, e)
    }

    override fun installPackage(pfd: ParcelFileDescriptor, fileSize: Long): Int {
        log("installPackage() called — fileSize=$fileSize")
        log("Process UID: ${android.os.Process.myUid()}, PID: ${android.os.Process.myPid()}")

        return try {
            // Use "pm install -S <size>" which reads the APK from stdin
            val command = arrayOf("pm", "install", "-S", fileSize.toString())
            log("Executing: ${command.joinToString(" ")}")

            val process = Runtime.getRuntime().exec(command)

            // Pipe the APK from the ParcelFileDescriptor to pm's stdin
            val writeThread = Thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        process.outputStream.use { output ->
                            val buffer = ByteArray(65536)
                            var bytesWritten = 0L
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesWritten += read
                            }
                            output.flush()
                            log("APK piped to pm stdin: $bytesWritten bytes (expected: $fileSize)")
                        }
                    }
                } catch (e: Exception) {
                    logE("Error piping APK to pm stdin", e)
                }
            }
            writeThread.start()

            // Read stdout/stderr for result
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()

            writeThread.join(120_000) // 2 minute timeout for write thread

            val exitCode = process.waitFor()
            log("pm install — exitCode=$exitCode, stdout='$stdout', stderr='$stderr'")

            if (exitCode == 0 && stdout.contains("Success")) {
                log("Install SUCCESS")
                STATUS_SUCCESS
            } else {
                logE("Install FAILED — exitCode=$exitCode, stdout='$stdout', stderr='$stderr'")
                STATUS_FAILURE
            }
        } catch (e: Exception) {
            logE("installPackage() exception", e)
            STATUS_FAILURE
        }
    }

    override fun uninstallPackage(packageName: String): Int {
        log("uninstallPackage() called for: $packageName")
        return try {
            val command = arrayOf("pm", "uninstall", packageName)
            log("Executing: ${command.joinToString(" ")}")

            val process = Runtime.getRuntime().exec(command)
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()

            log("pm uninstall — exitCode=$exitCode, stdout='$stdout', stderr='$stderr'")

            if (exitCode == 0 && stdout.contains("Success")) {
                log("Uninstall SUCCESS")
                STATUS_SUCCESS
            } else {
                logE("Uninstall FAILED — exitCode=$exitCode, stdout='$stdout', stderr='$stderr'")
                STATUS_FAILURE
            }
        } catch (e: Exception) {
            logE("uninstallPackage() exception", e)
            STATUS_FAILURE
        }
    }

    override fun destroy() {
        log("destroy() — service being unbound")
    }
}

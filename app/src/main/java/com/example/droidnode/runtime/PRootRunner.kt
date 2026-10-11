package com.example.droidnode.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Execution model for command line results.
 */
sealed class ProcessOutput {
    data class Stdout(val line: String) : ProcessOutput()
    data class Stderr(val line: String) : ProcessOutput()
    data class Completed(val exitCode: Int) : ProcessOutput()
    data class Error(val throwable: Throwable) : ProcessOutput()
}

/**
 * Manages PRoot invocation and handles real-time streams using ProcessBuilder.
 */
class PRootRunner(private val context: Context) {

    companion object {
        private const val TAG = "PRootRunner"
    }

    private val extractor = AssetExtractor(context)
    private var activeServerProcess: Process? = null

    /**
     * Resolves the path to the executable proot binary.
     * Note: In production, proot is stored in context.applicationInfo.nativeLibraryDir/libproot.so
     */
    fun getPRootBinary(): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val prootInNative = File(nativeDir, "libproot.so")
        if (prootInNative.exists()) {
            return prootInNative
        }

        // Fallback for custom filesDir extraction if targetSdk < 29
        val fallback = File(extractor.rootfsDir, "bin/proot")
        if (fallback.exists()) {
            fallback.setExecutable(true, false)
            return fallback
        }

        throw IllegalStateException("PRoot binary not found in ${prootInNative.absolutePath}")
    }

    /**
     * Builds the standard PRoot arguments list:
     * -0: Rootless root UID simulation (fake uid 0 / gid 0)
     * -r: Root directory of guest filesystem (chroot)
     * -b: Bind mounts for Android virtual filesystems (/dev, /proc, /sys)
     * -w: Initial working directory (/home)
     */
    private fun buildPRootArgs(guestCommand: List<String>): List<String> {
        val prootBin = getPRootBinary().absolutePath
        val rootfsPath = extractor.rootfsDir.absolutePath
        val homePath = extractor.homeDir.absolutePath
        val tmpPath = extractor.tmpDir.absolutePath

        val args = mutableListOf(
            prootBin,
            "-0", // Fake root user
            "-r", rootfsPath, // Rootfs directory
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$homePath:/home", // Mount user workspace to /home
            "-b", "$tmpPath:/tmp",
            "-w", "/home" // Working directory
        )

        // MOUNT INTERNAL STORAGE: /sdcard/www -> /www and /home/www inside PRoot
        // Resolves standard shared internal storage (/sdcard or /storage/emulated/0)
        val sdcardWww = File(android.os.Environment.getExternalStorageDirectory(), "www")
        if (!sdcardWww.exists()) {
            sdcardWww.mkdirs()
        }
        if (sdcardWww.exists() && sdcardWww.canRead()) {
            args.add("-b")
            args.add("${sdcardWww.absolutePath}:/www")
            args.add("-b")
            args.add("${sdcardWww.absolutePath}:/home/www")
            Log.i(TAG, "Mounted /sdcard/www bind to /www and /home/www successfully")
        } else {
            Log.w(TAG, "Storage /sdcard/www not accessible. Check MANAGE_EXTERNAL_STORAGE permission!")
        }

        // Append the guest command to execute inside rootfs
        args.addAll(guestCommand)
        return args
    }

    /**
     * Configures the Linux environment map for PRoot and Node/NPM.
     */
    private fun configureEnvironment(env: MutableMap<String, String>) {
        env["HOME"] = "/home"
        env["USER"] = "root"
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/bin:/usr/bin:/usr/local/bin:/sbin:/usr/sbin"
        env["TMPDIR"] = "/tmp"
        env["SHELL"] = "/bin/sh"
        env["LANG"] = "en_US.UTF-8"
        env["NODE_PATH"] = "/home/node_modules"
        env["PORT"] = "8080"
    }

    /**
     * Executes a one-off command (e.g., npm install <package>) and streams output in real-time.
     */
    fun executeStreamingCommand(command: List<String>): Flow<ProcessOutput> = channelFlow {
        val fullArgs = buildPRootArgs(command)
        Log.d(TAG, "Launching: ${fullArgs.joinToString(" ")}")

        var process: Process? = null
        try {
            val processBuilder = ProcessBuilder(fullArgs)
            processBuilder.directory(extractor.homeDir)
            configureEnvironment(processBuilder.environment())

            process = processBuilder.start()

            // Stream stdout in IO thread
            launch(Dispatchers.IO) {
                readLines(process.inputStream) { line ->
                    trySend(ProcessOutput.Stdout(line))
                }
            }

            // Stream stderr in IO thread
            launch(Dispatchers.IO) {
                readLines(process.errorStream) { line ->
                    trySend(ProcessOutput.Stderr(line))
                }
            }

            // Await completion
            val exitCode = withContext(Dispatchers.IO) {
                process.waitFor()
            }
            trySend(ProcessOutput.Completed(exitCode))

        } catch (e: Exception) {
            Log.e(TAG, "Process execution failed", e)
            trySend(ProcessOutput.Error(e))
        } finally {
            process?.destroy()
        }
    }

    /**
     * Runs npm install for a target package.
     */
    fun npmInstall(packageName: String): Flow<ProcessOutput> {
        val command = listOf("/usr/bin/npm", "install", packageName, "--save", "--no-audit", "--no-fund")
        return executeStreamingCommand(command)
    }

    /**
     * Starts the long-running Node.js server (node server.js).
     */
    suspend fun startServerProcess(
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
        onExit: (Int) -> Unit
    ): Process = withContext(Dispatchers.IO) {
        stopServerProcess()

        val fullArgs = buildPRootArgs(listOf("/usr/bin/node", "server.js"))
        val processBuilder = ProcessBuilder(fullArgs)
        processBuilder.directory(extractor.homeDir)
        configureEnvironment(processBuilder.environment())

        val process = processBuilder.start()
        activeServerProcess = process

        CoroutineScope(Dispatchers.IO).launch {
            readLines(process.inputStream) { onStdout(it) }
        }

        CoroutineScope(Dispatchers.IO).launch {
            readLines(process.errorStream) { onStderr(it) }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val code = process.waitFor()
            activeServerProcess = null
            onExit(code)
        }

        process
    }

    /**
     * Stops the running Node.js server safely.
     */
    fun stopServerProcess() {
        activeServerProcess?.let { proc ->
            try {
                proc.destroy() // SIGTERM
                proc.waitFor()
            } catch (e: Exception) {
                proc.destroyForcibly() // SIGKILL
            }
            activeServerProcess = null
        }
    }

    fun isServerRunning(): Boolean {
        return activeServerProcess?.isAlive == true
    }

    private inline fun readLines(stream: InputStream, onLine: (String) -> Unit) {
        try {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    onLine(line)
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // Stream closed when process terminates
        }
    }
}
 
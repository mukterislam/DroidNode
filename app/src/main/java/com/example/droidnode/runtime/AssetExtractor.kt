package com.example.droidnode.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Handles rootfs bootstrap extraction from APK assets into internal storage.
 * Preserves Linux file permissions (755 / 644) and symbolic links.
 */
class AssetExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AssetExtractor"
        private const val BOOTSTRAP_VERSION_KEY = "installed_bootstrap_v1"
    }

    /**
     * Target rootfs directory: /data/user/0/com.example.droidnode/files/usr
     */
    val rootfsDir: File get() = File(context.filesDir, "usr")
    val homeDir: File get() = File(context.filesDir, "home")
    val tmpDir: File get() = File(context.filesDir, "tmp")

    /**
     * Checks if the environment is already extracted and valid.
     */
    fun isEnvironmentReady(): Boolean {
        val nodeBinary = File(rootfsDir, "bin/node")
        val prefs = context.getSharedPreferences("droidnode_prefs", Context.MODE_PRIVATE)
        return nodeBinary.exists() && nodeBinary.canExecute() && prefs.getBoolean(BOOTSTRAP_VERSION_KEY, false)
    }

    /**
     * Extracts rootfs tarball and sets up directories with coroutines.
     * @param onProgress Callback with progress message and percentage (0..100)
     */
    suspend fun extractEnvironment(
        onProgress: (String, Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress("Preparing target directories...", 0.05f)
            
            // 1. Ensure target filesystem layout
            rootfsDir.mkdirs()
            homeDir.mkdirs()
            tmpDir.mkdirs()

            // 2. Resolve target ABI asset
            val abi = selectSupportedAbi()
            val assetPath = "bootstrap/bootstrap-$abi.tar.xz"
            val fallbackPath = "bootstrap/bootstrap-$abi.tar.gz"

            onProgress("Locating bootstrap for architecture [$abi]...", 0.10f)
            val (inputStream, isXz) = openBootstrapStream(assetPath, fallbackPath)

            onProgress("Extracting Linux rootfs & restoring symlinks...", 0.20f)
            unpackTarStream(inputStream, isXz, rootfsDir, onProgress)

            // 3. Deploy default package.json and server.js into home directory
            deployDefaultServerAssets()

            // 4. Mark execution bits on binaries in usr/bin and usr/libexec
            onProgress("Applying executable bits to Linux binaries...", 0.90f)
            fixExecutablePermissions(File(rootfsDir, "bin"))
            fixExecutablePermissions(File(rootfsDir, "libexec"))

            // 5. Store installation flag in SharedPreferences
            context.getSharedPreferences("droidnode_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(BOOTSTRAP_VERSION_KEY, true)
                .apply()

            onProgress("Environment ready!", 1.0f)
        }
    }

    private fun selectSupportedAbi(): String {
        for (supportedAbi in Build.SUPPORTED_ABIS) {
            when {
                supportedAbi.startsWith("arm64-v8a") -> return "arm64-v8a"
                supportedAbi.startsWith("armeabi-v7a") -> return "armeabi-v7a"
                supportedAbi.startsWith("x86_64") -> return "x86_64"
            }
        }
        return "arm64-v8a" // Default fallback
    }

    private fun openBootstrapStream(xzPath: String, gzPath: String): Pair<InputStream, Boolean> {
        val assetManager = context.assets
        return try {
            Pair(assetManager.open(xzPath), true)
        } catch (_: IOException) {
            Pair(assetManager.open(gzPath), false)
        }
    }

    /**
     * Robust TAR unpacking that handles:
     * 1. Directories & standard files
     * 2. Symbolic links (entry.isSymbolicLink) via Os.symlink
     * 3. Linux mode permissions (0755 vs 0644)
     */
    private fun unpackTarStream(
        rawStream: InputStream,
        isXz: Boolean,
        destinationDir: File,
        onProgress: (String, Float) -> Unit
    ) {
        val bufferedIn = BufferedInputStream(rawStream, 65536)
        val decompressedStream: InputStream = if (isXz) {
            XZCompressorInputStream(bufferedIn)
        } else {
            GZIPInputStream(bufferedIn)
        }

        TarArchiveInputStream(decompressedStream).use { tarIn ->
            var entry: TarArchiveEntry? = tarIn.nextEntry
            var count = 0

            while (entry != null) {
                count++
                val outputFile = File(destinationDir, entry.name)

                // Path traversal security check (Zip Slip vulnerability prevention)
                val canonicalDest = destinationDir.canonicalPath
                val canonicalOut = outputFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest)) {
                    throw SecurityException("Tar entry attempted directory traversal: ${entry.name}")
                }

                if (count % 250 == 0) {
                    onProgress("Extracted $count files: ${entry.name.takeLast(28)}", 0.3f + (count % 1000) * 0.0005f)
                }

                when {
                    entry.isDirectory -> {
                        outputFile.mkdirs()
                    }

                    entry.isSymbolicLink -> {
                        // CRITICAL: Linux symbolic links!
                        // e.g., 'node' -> 'node.v20' or 'sh' -> '/bin/bash'
                        outputFile.parentFile?.mkdirs()
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        try {
                            Os.symlink(entry.linkName, outputFile.absolutePath)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed creating symlink ${outputFile.name} -> ${entry.linkName}: ${e.message}")
                        }
                    }

                    else -> {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { fileOut ->
                            tarIn.copyTo(fileOut, bufferSize = 32768)
                        }

                        // Apply original Linux file mode bits
                        val mode = entry.mode
                        if (mode and 0b001_000_000 != 0) { // User execute bit
                            outputFile.setExecutable(true, false)
                        }
                        outputFile.setReadable(true, false)
                    }
                }

                entry = tarIn.nextEntry
            }
        }
    }

    private fun fixExecutablePermissions(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
                try {
                    Os.chmod(file.absolutePath, 0b111_101_101) // 0755
                } catch (e: Exception) {
                    Log.d(TAG, "chmod 755 on ${file.name}: ${e.message}")
                }
            }
        }
    }

    private fun deployDefaultServerAssets() {
        val serverJs = File(homeDir, "server.js")
        if (!serverJs.exists()) {
            val defaultCode = """
                const http = require('http');
                const fs = require('fs');
                const path = require('path');
                const port = process.env.PORT || 8080;

                // Path to mounted internal storage directory /sdcard/www
                const WWW_DIR = '/www';

                const server = http.createServer((req, res) => {
                    let reqUrl = req.url === '/' ? '/index.html' : req.url;
                    let filePath = path.join(WWW_DIR, reqUrl);

                    // Check if file exists in /sdcard/www
                    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                        const ext = path.extname(filePath).toLowerCase();
                        const mimeTypes = {
                            '.html': 'text/html',
                            '.css': 'text/css',
                            '.js': 'text/javascript',
                            '.json': 'application/json',
                            '.png': 'image/png',
                            '.jpg': 'image/jpeg'
                        };
                        const contentType = mimeTypes[ext] || 'application/octet-stream';
                        res.writeHead(200, { 'Content-Type': contentType });
                        return fs.createReadStream(filePath).pipe(res);
                    }

                    // Fallback JSON API endpoint
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({
                        status: 'online',
                        engine: 'Node.js ' + process.version,
                        platform: process.platform,
                        storageLocation: '/sdcard/www (mounted to /www)',
                        uptime: process.uptime(),
                        message: 'Serving files from /sdcard/www on Android'
                    }));
                });

                server.listen(port, '0.0.0.0', () => {
                    console.log(`[DroidNode Server] Serving /sdcard/www on http://0.0.0.0:${port}`);
                });
            """.trimIndent()
            serverJs.writeText(defaultCode)
        }

        // Initialize /sdcard/www directory with a starter HTML file
        seedSdcardWwwDirectory()

        val packageJson = File(homeDir, "package.json")
        if (!packageJson.exists()) {
            val defaultPkg = """
                {
                  "name": "droidnode-server",
                  "version": "1.0.0",
                  "description": "Embedded Android Node.js App",
                  "main": "server.js",
                  "scripts": {
                    "start": "node server.js"
                  },
                  "dependencies": {}
                }
            """.trimIndent()
            packageJson.writeText(defaultPkg)
        }
    }

    /**
     * Seeds the /sdcard/www directory with a starter index.html
     * Users can edit this file using any Android code editor (Acode, QuickEdit, etc.)
     */
    private fun seedSdcardWwwDirectory() {
        try {
            val sdcardWww = File(android.os.Environment.getExternalStorageDirectory(), "www")
            if (!sdcardWww.exists()) {
                sdcardWww.mkdirs()
            }
            val indexHtml = File(sdcardWww, "index.html")
            if (!indexHtml.exists()) {
                indexHtml.writeText("""
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>My Android Node Server</title>
                      <style>
                        body { font-family: system-ui, sans-serif; background: #0f172a; color: #f8fafc; padding: 2rem; }
                        .card { background: #1e293b; padding: 1.5rem; border-radius: 12px; max-width: 600px; margin: 0 auto; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
                        h1 { color: #38bdf8; margin-top: 0; }
                        code { background: #334155; padding: 2px 6px; border-radius: 4px; color: #4ade80; }
                      </style>
                    </head>
                    <body>
                      <div class="card">
                        <h1>🚀 Node.js Running from /sdcard/www!</h1>
                        <p>This web page is hosted directly from your Android phone's internal storage:</p>
                        <p><code>/sdcard/www/index.html</code></p>
                        <p>Edit this file using any Android code editor (Acode, QuickEdit, MT Manager) and refresh the page to see live updates!</p>
                      </div>
                    </body>
                    </html>
                """.trimIndent())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not seed /sdcard/www: ${e.message}")
        }
    }
}

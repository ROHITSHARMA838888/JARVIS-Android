package com.jarvis.assistant

import java.util.concurrent.TimeUnit

object RootExecutor {

    fun isRootAvailable(): Boolean {
        val (ok, output) = run("id")
        return ok && output.contains("uid=0")
    }

    fun run(command: String, timeoutSeconds: Long = 8): Pair<Boolean, String> {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Pair(false, "Root command timed out.")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.exitValue()
            Pair(exitCode == 0, output)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Root execution failed.")
        }
    }
}

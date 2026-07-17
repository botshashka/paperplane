package dev.paperplane.cli.devserver

import com.google.gson.Gson
import com.google.gson.JsonParseException
import dev.paperplane.cli.plugins.atomicMoveOrFallback
import dev.paperplane.cli.ui.TerminalUI
import java.io.File
import java.io.IOException

/** Outcome of waiting for the host to answer a [LoadRequest]. */
internal sealed interface LoadWaitResult {
  /** Host loaded/reloaded the plugin. [report] is null only on a torn/unparseable success flag. */
  data class Ok(val report: LoadReport?) : LoadWaitResult

  /**
   * Host rejected the load. [message] is human-readable; [report] is null on unparseable content.
   */
  data class Failed(val message: String, val report: LoadReport?) : LoadWaitResult

  /** No result flag matched [expectedRequestId] within the timeout. */
  object TimedOut : LoadWaitResult

  /** The server process died while waiting — no result can ever arrive. */
  object ServerExited : LoadWaitResult
}

/**
 * Waits for the host to answer a [LoadRequest]. Shared by [DevSession.startServerAndReport]
 * (initial load) and [HotReloadMode] (rebuild reloads). A `fun interface` so tests can inject a
 * scripted waiter as a lambda without touching the filesystem.
 *
 * [isAlive] is polled between filesystem checks; when it reports the server process dead the wait
 * ends immediately with [LoadWaitResult.ServerExited] instead of running out the full timeout.
 */
internal fun interface LoadResultWaiter {
  fun await(
      serverDir: File,
      expectedRequestId: String,
      timeoutMs: Long,
      isAlive: () -> Boolean,
  ): LoadWaitResult
}

/**
 * Polls `.paperplane/load-complete` / `load-failed` for a result matching the request just written,
 * filtering on `requestId` so a stale result from a previous reload is never mistaken for this
 * one's.
 */
internal class PollingLoadResultWaiter(private val ui: TerminalUI) : LoadResultWaiter {
  companion object {
    private const val POLL_INTERVAL_MS = 100L
  }

  private val gson = Gson()

  override fun await(
      serverDir: File,
      expectedRequestId: String,
      timeoutMs: Long,
      isAlive: () -> Boolean,
  ): LoadWaitResult {
    val complete = LoadRequest.completeFlag(serverDir)
    val failed = LoadRequest.failedFlag(serverDir)
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      // Read the durable result flags BEFORE checking liveness: if the host wrote a matching result
      // and the process then died within the same poll window, the reload genuinely completed and
      // that on-disk result must win over the subsequent process death.
      readComplete(complete, expectedRequestId)?.let {
        return it
      }
      readFailed(failed, expectedRequestId)?.let {
        return it
      }
      if (!isAlive()) return LoadWaitResult.ServerExited
      Thread.sleep(POLL_INTERVAL_MS)
    }
    return LoadWaitResult.TimedOut
  }

  /**
   * Consumes the load-complete flag if present. Returns the resolved [LoadWaitResult], or null to
   * keep polling — the latter when the flag is absent or carries a stale requestId (dropped so it
   * can't be re-read forever). A torn/legacy plain-text flag degrades to success with a warning.
   */
  private fun readComplete(flag: File, expectedRequestId: String): LoadWaitResult? {
    val text = claim(flag) ?: return null
    val report = parseOrNull(text)
    if (report == null) {
      ui.status("⚠ load result was not valid JSON — assuming the load succeeded")
      return LoadWaitResult.Ok(null)
    }
    return if (report.requestId == expectedRequestId) LoadWaitResult.Ok(report) else null
  }

  /**
   * Consumes the load-failed flag if present; see [readComplete] for the null-to-keep-polling
   * contract. Unparseable content is carried verbatim as the failure message.
   */
  private fun readFailed(flag: File, expectedRequestId: String): LoadWaitResult? {
    val text = claim(flag) ?: return null
    val report = parseOrNull(text)
    if (report == null) {
      ui.status("⚠ load result was not valid JSON — treating its content as the failure message")
      return LoadWaitResult.Failed(text.trim().ifEmpty { "plugin load failed" }, null)
    }
    return if (report.requestId == expectedRequestId) {
      LoadWaitResult.Failed(report.message ?: "plugin load failed", report)
    } else {
      null
    }
  }

  /**
   * Claims [flag] by renaming it aside before reading, so a concurrent replace by the companion can
   * never be lost mid-read: the companion's atomic move either lands before the claim (this poll
   * reads the new content) or after it (the flag reappears for the next poll). Renaming first also
   * means the flag path itself is never held open, so the companion's `REPLACE_EXISTING` move can't
   * hit a Windows sharing violation against this reader. Returns the flag's content, or null when
   * the flag is absent or vanished mid-claim (keep polling).
   */
  private fun claim(flag: File): String? {
    if (!flag.exists()) return null
    val claimed = File(flag.parentFile, ".${flag.name}.claim")
    try {
      atomicMoveOrFallback(flag.toPath(), claimed.toPath())
    } catch (_: IOException) {
      return null
    }
    val text = claimed.readText()
    claimed.delete()
    return text
  }

  private fun parseOrNull(text: String): LoadReport? =
      try {
        gson.fromJson(text, LoadReport::class.java)
      } catch (_: JsonParseException) {
        null
      }
}

package dev.paperplane.cli.util

import java.io.File
import java.security.MessageDigest

private const val DIGEST_BUFFER_BYTES = 64 * 1024

/** Computes [algorithm]'s digest (e.g. "SHA-256") of [file] as a lowercase hex string. */
internal fun fileDigestHex(file: File, algorithm: String): String {
  val digest = MessageDigest.getInstance(algorithm)
  file.inputStream().use { input ->
    val buf = ByteArray(DIGEST_BUFFER_BYTES)
    while (true) {
      val read = input.read(buf)
      if (read <= 0) break
      digest.update(buf, 0, read)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

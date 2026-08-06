package com.lakshaysethi.victronbleexporter.diag

/**
 * Tiny JSON string escaper for the diagnostics payloads. Kept dependency-free so
 * the payload builder and its unit tests run on the plain JVM (org.json only
 * exists on Android/Robolectric, not in the JVM unit-test classpath).
 */
internal object Json {
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Quote + escape a string for use inside a JSON document. */
    fun str(s: String): String = "\"${escape(s)}\""
}

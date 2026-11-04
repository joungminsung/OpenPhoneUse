package com.phoneuse.service

/**
 * What open_uri may start. The model writes the URI, and a page or message can plant one: an `intent:` URI could
 * name one of our non-exported activities (started from the accessibility service, our own uid, it would run),
 * or carry `FLAG_GRANT_*` launch flags with our attachment provider's content URI to hand another app our files.
 * So our own package, our providers' URIs and every launch flag but [launchFlags] are refused or dropped.
 */
object OpenUriPolicy {
    /** Null when [uri] may be opened, else why not. [ownPackage] is our application id. */
    fun refusal(uri: String, ownPackage: String): String? {
        val lower = uri.lowercase()
        val pkg = ownPackage.lowercase()
        if ("content://$pkg" in lower) return "this app's own files cannot be opened"
        if (!lower.startsWith("intent:")) return null
        val extras = lower.substringAfter("#intent;", "").split(';')
        for (part in extras) {
            val target = when {
                part.startsWith("component=") -> part.removePrefix("component=").substringBefore('/')
                part.startsWith("package=") -> part.removePrefix("package=")
                else -> continue
            }
            if (target == pkg) return "this app's own screens cannot be opened"
        }
        return null
    }

    /**
     * The launch flags for an intent with [action]. A Settings page also clears the Settings task: with NEW_TASK
     * alone, Android only brings an already open Settings back to whatever page it was on, and the page asked for
     * never shows. Other apps keep their task (a map or a browser takes the new link itself).
     */
    fun launchFlags(action: String?): Int =
        if (action?.startsWith("android.settings.") == true) NEW_TASK or CLEAR_TASK else NEW_TASK

    /** Intent.FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_CLEAR_TASK, spelled out so this stays plain Kotlin. */
    const val NEW_TASK = 0x10000000
    const val CLEAR_TASK = 0x00008000
}

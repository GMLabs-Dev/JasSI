package dev.gmlabs.jarvis

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

class AppResolver(private val context: Context) {
    fun launch(nameOrPackage: String): String {
        val query = nameOrPackage.trim()
        val packageManager = context.packageManager
        val direct = packageManager.getLaunchIntentForPackage(query)
        if (direct != null) return start(direct, query)

        val candidates = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
        val match = candidates.firstOrNull { info ->
            val activity = info.activityInfo
            val label = info.loadLabel(packageManager).toString()
            activity.packageName.equals(query, ignoreCase = true) ||
                label.equals(query, ignoreCase = true) ||
                label.replace(Regex("[^a-z0-9]"), "")
                    .equals(query.replace(Regex("[^a-z0-9]"), ""), ignoreCase = true)
        }
        return if (match == null) {
            "I could not find a launchable app named $query."
        } else {
            start(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(match.activityInfo.packageName, match.activityInfo.name),
                match.loadLabel(packageManager).toString()
            )
        }
    }

    private fun start(intent: Intent, label: String): String {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening $label."
        } catch (error: Exception) {
            "I found $label, but Android could not launch it: ${error.message ?: "unknown error"}"
        }
    }
}

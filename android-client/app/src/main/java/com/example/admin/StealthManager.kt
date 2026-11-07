package com.example.admin

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object StealthManager {
    private const val ALIAS = "com.example.admin.MainActivityLauncher"

    private fun alias(ctx: Context) = ComponentName(ctx.packageName, ALIAS)

    fun hide(ctx: Context) {
        ctx.packageManager.setComponentEnabledSetting(
            alias(ctx),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        Prefs.setHidden(ctx, true)
    }

    fun unhide(ctx: Context) {
        ctx.packageManager.setComponentEnabledSetting(
            alias(ctx),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        Prefs.setHidden(ctx, false)
    }

    fun isHidden(ctx: Context): Boolean {
        val state = ctx.packageManager.getComponentEnabledSetting(alias(ctx))
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun toggle(ctx: Context) {
        if (isHidden(ctx)) unhide(ctx) else hide(ctx)
    }
}

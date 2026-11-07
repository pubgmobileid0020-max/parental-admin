package com.example.admin

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "admin_prefs"
    private const val KEY_SERVER = "server"
    private const val KEY_PSK = "psk"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_RUNNING = "running"
    private const val KEY_DEVICE_NAME = "device_name"

    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getServer(ctx: Context): String = sp(ctx).getString(KEY_SERVER, "") ?: ""
    fun setServer(ctx: Context, v: String) { sp(ctx).edit().putString(KEY_SERVER, v).apply() }
    fun getPsk(ctx: Context): String = sp(ctx).getString(KEY_PSK, "") ?: ""
    fun setPsk(ctx: Context, v: String) { sp(ctx).edit().putString(KEY_PSK, v).apply() }
    fun isHidden(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_HIDDEN, false)
    fun setHidden(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(KEY_HIDDEN, v).apply() }
    fun isRunning(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RUNNING, false)
    fun setRunning(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(KEY_RUNNING, v).apply() }
    
    fun getDeviceName(ctx: Context): String = sp(ctx).getString(KEY_DEVICE_NAME, "") ?: ""
    fun setDeviceName(ctx: Context, v: String) { sp(ctx).edit().putString(KEY_DEVICE_NAME, v).apply() }
}

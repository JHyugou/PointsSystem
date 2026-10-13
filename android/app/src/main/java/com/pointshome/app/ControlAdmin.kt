package com.pointshome.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager

internal object ControlAdmin {
    fun component(context: Context) = ComponentName(context, HouseDeviceAdmin::class.java)
    fun manager(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun isOwner(context: Context) = manager(context).isDeviceOwnerApp(context.packageName)
    fun isAdmin(context: Context) = manager(context).isAdminActive(component(context))
    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, AppLimitService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    fun protect(context: Context, enabled: Boolean) {
        if (isOwner(context)) {
            val dpm = manager(context)
            dpm.setUninstallBlocked(component(context), context.packageName, enabled)
            if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(component(context),
                if (enabled) listOf(context.packageName) else emptyList())
        } else if (!enabled && isAdmin(context)) manager(context).removeActiveAdmin(component(context))
    }
    fun suspend(context: Context, packages: Set<String>, suspended: Boolean): List<String> {
        if (!isOwner(context) || Build.VERSION.SDK_INT < 24 || packages.isEmpty()) return emptyList()
        return manager(context).setPackagesSuspended(component(context), packages.toTypedArray(), suspended).toList()
    }
    @Suppress("DEPRECATION")
    fun releaseOwnership(context: Context, packages: Set<String>) {
        check(isOwner(context)) { "本应用没有整机管理权限" }
        val failures = suspend(context, packages, false)
        check(failures.isEmpty()) { "部分应用尚未恢复，请先恢复应用再解除整机管理" }
        protect(context, false)
        // Explicit parent confirmation is required in the UI. Undo every policy we set first.
        manager(context).clearDeviceOwnerApp(context.packageName)
        check(!isOwner(context)) { "系统未解除整机管理，请由家长检查系统设置" }
    }
    fun protectedPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val names = mutableSetOf(context.packageName, "com.android.systemui", "com.android.settings",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller")
        listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:"))).forEach { intent ->
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach { names += it.activityInfo.packageName }
        }
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let { names += it }
        names += inputMethodPackages(context)
        return names
    }
    fun inputMethodPackages(context: Context): Set<String> =
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.enabledInputMethodList.orEmpty().map { it.serviceInfo.packageName }.toSet()
}

class HouseDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "停用管理员会降低本机限制的保护能力。请由家长先在积分小屋输入密码解除保护。"
    override fun onEnabled(context: Context, intent: Intent) {
        if (ControlAdmin.isOwner(context)) ControlAdmin.protect(context, true)
    }
}

class ControlBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Ownership restrictions persist across boots. Keep applications suspended until counting resumes.
        val result = goAsync()
        Thread {
            try {
                val state = LocalControlStore.get(context).snapshot()
                if (state.enabled) ControlAdmin.suspend(context, state.packages, true)
            } finally { result.finish() }
        }.start()
    }
}

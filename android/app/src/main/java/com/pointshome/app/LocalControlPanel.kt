package com.pointshome.app

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.UUID

internal fun timeLabel(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1000
    return "${seconds / 60} 分 ${seconds % 60} 秒"
}

/** Grant delivery is the only phone state sent to the server; foreground applications stay local. */
internal suspend fun collectTimeGrants(client: ApiClient, token: String, store: LocalControlStore) {
    val state = store.snapshot()
    if (!state.configured || state.serverUrl != client.serverAddress) return
    val device = TimeDeviceRegistration(state.deviceId, state.deviceKey)
    for (grant in client.timeGrants(token, device)) {
        withContext(Dispatchers.IO) { store.applyGrant(grant) }
        // Commit the local credit before acknowledging. A failed acknowledgement is safe to retry.
        client.acknowledgeTime(token, grant.id, device)
    }
}

@Composable
internal fun TimeGrantSync(account: AccountInfo, token: String, client: ApiClient,
                          store: LocalControlStore, onChanged: () -> Unit) {
    val state by store.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(account.id, token, client, state.deviceKey, foreground) {
        if (!foreground || !store.matches(account.id, client.serverAddress)) return@LaunchedEffect
        while (true) {
            try {
                val before = store.snapshot().appliedGrants.size
                collectTimeGrants(client, token, store)
                if (before != store.snapshot().appliedGrants.size) onChanged()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Offline time remains usable. Manual sync displays actionable errors. */ }
            delay(30_000)
        }
    }
}

@Composable
internal fun TimeBalanceCard(store: LocalControlStore, buttonLabel: String = "查看时间与本机设置 →", onOpen: () -> Unit) {
    val state by store.state.collectAsState()
    val running by AppLimitService.running.collectAsState()
    val live by AppLimitService.remaining.collectAsState()
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = HouseSoftGreen) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("限制应用使用时间", fontWeight = FontWeight.SemiBold, color = HouseInk)
            Text(timeLabel(if (running) live else state.remainingMillis), color = HouseGreen,
                fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(if (state.enabled && !running) "拦截服务未运行，请家长检查权限" else "所有受限应用共用这份时间。",
                color = HouseMuted, fontSize = 13.sp)
            TextButton(onClick = onOpen) { Text(buttonLabel) }
        }
    }
}

@Composable
internal fun ParentPinDialog(store: LocalControlStore, title: String = "输入家长密码",
                             onDismiss: () -> Unit, onConfirmed: () -> Unit,
                             onRecovery: (() -> Unit)? = null) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    HouseDialog(title, "锁", "验证密码", busy = busy, onDismiss = onDismiss, onConfirm = {
        if (!busy) scope.launch {
            busy = true
            try {
                if (withContext(Dispatchers.IO) { store.verifyPin(pin) }) {
                    pin = ""; onConfirmed()
                } else { error = "家长密码不正确"; pin = "" }
            } catch (ex: Exception) { error = ex.message ?: "验证失败" }
            finally { busy = false }
        }
    }) {
        HouseTextField(pin, { pin = it }, "本机家长密码", KeyboardType.Password, secret = true)
        HouseHint("此密码由家长在这台手机上设置，与孩子的登录密码不同。")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        onRecovery?.let { TextButton(onClick = it, enabled = !busy) { Text("忘记密码？由已关联家长重设") } }
    }
}

private data class LaunchableApp(val packageName: String, val label: String)

@Composable
internal fun LocalControlPanel(account: AccountInfo, token: String, client: ApiClient,
                               store: LocalControlStore, onRewards: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    val running by AppLimitService.running.collectAsState()
    val live by AppLimitService.remaining.collectAsState()
    val serviceError by AppLimitService.problem.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var unlockedUntil by remember { mutableLongStateOf(0) }
    var unlockRevision by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var selection by remember { mutableStateOf(state.packages) }
    var enabledDraft by remember { mutableStateOf(state.enabled) }
    var search by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(20) }
    var resetPin by remember { mutableStateOf(false) }
    var changePin by remember { mutableStateOf(false) }
    var releaseOwner by remember { mutableStateOf(false) }
    var permissionsRevision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val unlocked = unlockedUntil > SystemClock.elapsedRealtime()
    val admin = remember(permissionsRevision) { ControlAdmin.isAdmin(context) }
    val owner = remember(permissionsRevision) { ControlAdmin.isOwner(context) }
    val accessibility = remember(permissionsRevision) { ControlAdmin.accessibilityEnabled(context) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlockedUntil = 0
            if (event == Lifecycle.Event.ON_RESUME) permissionsRevision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(unlockedUntil) {
        if (unlockedUntil > 0) { delay((unlockedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)); unlockedUntil = 0 }
    }
    LaunchedEffect(unlockRevision) {
        if (unlockRevision == 0) return@LaunchedEffect
        selection = store.snapshot().packages; enabledDraft = store.snapshot().enabled
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val excluded = ControlAdmin.protectedPackages(context)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName !in excluded }
                .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null; notice = null
            try { block() } catch (cancel: CancellationException) { throw cancel }
            catch (ex: Exception) { error = ex.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun requireUnlocked(): Boolean {
        if (unlockedUntil <= SystemClock.elapsedRealtime()) { showPin = true; return false }
        return true
    }

    if (showPin) ParentPinDialog(store, onDismiss = { showPin = false }, onConfirmed = {
        showPin = false; unlockedUntil = SystemClock.elapsedRealtime() + 120_000; unlockRevision++
    })
    if (resetPin) ParentSetupDialog(account, client, store, recovery = true,
        onDismiss = { resetPin = false }, onSuccess = { resetPin = false; notice = "家长密码已重设" })
    if (changePin) NewPinDialog(onDismiss = { changePin = false }) { pin ->
        if (requireUnlocked()) action {
            withContext(Dispatchers.IO) { store.changePin(pin) }
            changePin = false; unlockedUntil = 0; notice = "家长密码已修改"
        }
    }
    if (releaseOwner) HouseDialog("解除整机管理", "锁", "确认解除", busy = busy,
        onDismiss = { releaseOwner = false }, onConfirm = {
            if (requireUnlocked()) action {
                withContext(Dispatchers.IO) {
                    val current = store.snapshot()
                    store.setRules(current.packages, false)
                    ControlAdmin.releaseOwnership(context, current.packages)
                }
                releaseOwner = false; permissionsRevision++; unlockedUntil = 0
                notice = "已解除本应用的整机管理和卸载防护"
            }
        }) {
        HouseHint("将解除受限应用和系统卸载保护，保留本机时间余额。重新开启整机管理需要重新配置设备，不能在这里一键恢复。")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (!state.configured) {
        ControlCard {
            Text("家长在这台手机上设置", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            HouseHint(if (state.damaged) "本机设置无法读取。请家长重新验证账号；丢失的本地余额不能自动恢复。"
                else "先由已关联孩子的家长验证账号，设置本机密码，然后选择需要限制的应用。")
            ParentSetupForm(account, client, store, recovery = false) { notice = "本机已绑定，请继续设置应用与权限" }
        }
        return
    }
    if (!store.matches(account.id, client.serverAddress)) {
        ControlCard { HouseHint("本机已绑定 ${state.childName}。请使用原孩子账号与服务器查看时间。") }
        return
    }
    TimeBalanceCard(store, buttonLabel = "前往兑换应用时间 →", onOpen = onRewards)
    ControlCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { action { collectTimeGrants(client, token, store); notice = "已同步家长批准的时间" } },
                enabled = !busy) { Text(if (busy) "同步中…" else "领取已批准时间") }
        }
        Text("兑换时间后需要家长批准。已到账的时间离线也能使用。", color = HouseMuted, fontSize = 13.sp)
        Text(if (state.enabled) "本机限制已开启 · ${state.packages.size} 个应用" else "本机限制尚未开启", color = HouseGreen)
        Text(when {
            owner -> "保护级别：整机管理（可阻止卸载）"
            admin -> "保护级别：基础管理员（卸载前需先停用管理员）"
            else -> "保护级别：普通应用"
        }, color = HouseInk)
        Text(if (accessibility && running) "应用拦截服务正在运行" else "应用拦截服务未运行，普通模式下无法限制其他应用",
            color = if (running) HouseMuted else MaterialTheme.colorScheme.error, fontSize = 13.sp)
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            color = HouseMuted, fontSize = 12.sp)
        HouseHint("基础管理员无法阻止孩子从系统设置停用权限、清除数据或卸载。只有整机管理才能提供系统级卸载保护；目前不会在这里重置手机。")
        serviceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = HouseGreen) }
        if (!unlocked) {
            Button(onClick = { showPin = true }) { Text("家长设置（需要密码）") }
            TextButton(onClick = { resetPin = true }) { Text("忘记本机密码？由已关联家长重设") }
        } else OutlinedButton(onClick = { unlockedUntil = 0 }) { Text("结束家长设置") }
    }
    if (unlocked) ControlCard {
        Text("选择受限应用", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("应用列表仅保存在本机，不上传使用记录。电话、桌面、系统设置和积分小屋始终可用。", color = HouseMuted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("开启应用限制", Modifier.weight(1f))
            Switch(enabledDraft, { if (requireUnlocked()) enabledDraft = it })
        }
        HouseTextField(search, { search = it; shown = 20 }, "搜索应用")
        val filtered = apps.filter { search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true) }
        if (apps.isEmpty()) Text("未发现可限制的应用，或应用列表正在读取。", color = HouseMuted)
        filtered.take(shown).forEach { app ->
            Row(Modifier.fillMaxWidth().clickable {
                if (requireUnlocked()) selection = if (app.packageName in selection) selection - app.packageName else selection + app.packageName
            }.padding(vertical = 5.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.Medium)
                    Text(app.packageName, color = HouseMuted, fontSize = 11.sp)
                }
                Checkbox(app.packageName in selection, onCheckedChange = null)
            }
        }
        if (filtered.size > shown) TextButton(onClick = { shown += 20 }) { Text("查看更多应用") }
        Button(onClick = {
            if (requireUnlocked()) action {
                if (enabledDraft && selection.isEmpty()) throw IllegalArgumentException("请先选择要限制的应用")
                if (enabledDraft && !ControlAdmin.accessibilityEnabled(context)) throw IllegalArgumentException("请先在下方开启应用拦截权限")
                withContext(Dispatchers.IO) {
                    val safe = selection - ControlAdmin.protectedPackages(context)
                    val old = store.snapshot()
                    ControlAdmin.suspend(context, old.packages - safe, false)
                    val failures = ControlAdmin.suspend(context, safe,
                        enabledDraft && (old.remainingMillis <= 0 || !AppLimitService.running.value))
                    if (failures.isNotEmpty()) throw IllegalStateException("部分应用不支持系统暂停，请取消选择后重试")
                    store.setRules(safe, enabledDraft)
                }
                notice = "本机限制已保存"; unlockedUntil = 0
            }
        }, enabled = !busy) { Text("保存应用限制") }
        OutlinedButton(onClick = {
            if (requireUnlocked()) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("开启／检查应用拦截权限") }
        Text("离开此页面会锁定家长设置，返回后请再次输入密码。", color = HouseMuted, fontSize = 12.sp)
        OutlinedButton(onClick = {
            if (requireUnlocked()) action {
                if (owner) {
                    withContext(Dispatchers.IO) { ControlAdmin.protect(context, true) }
                    notice = "系统卸载保护已开启"
                } else context.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ControlAdmin.component(context))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "增加卸载前的管理员确认步骤；普通管理员不能提供绝对防卸载。"))
            }
        }, enabled = !busy) { Text(if (owner) "开启系统卸载保护" else "开启基础卸载防护") }
        if (admin || owner) OutlinedButton(onClick = {
            if (requireUnlocked() && owner) releaseOwner = true
            else if (requireUnlocked()) action {
                withContext(Dispatchers.IO) { ControlAdmin.protect(context, false) }
                permissionsRevision++; notice = "卸载防护已解除"
            }
        }, enabled = !busy) { Text(if (owner) "解除整机管理与卸载防护" else "解除基础卸载防护") }
        OutlinedButton(onClick = { if (requireUnlocked()) changePin = true }) { Text("修改本机家长密码") }
        HouseHint("华为／荣耀系统可能在清理后台或重启后关闭无障碍权限。请家长在应用启动管理中允许本应用自动启动和后台运行，再用真机检查。")
    }
}

@Composable
private fun ControlCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun ParentSetupDialog(account: AccountInfo, client: ApiClient, store: LocalControlStore,
                              recovery: Boolean, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    // The form performs its own request; the dialog's secondary action only closes it.
    HouseDialog("验证家长并重设密码", "锁", "返回", onConfirm = onDismiss, onDismiss = onDismiss) {
        ParentSetupForm(account, client, store, recovery, onSuccess)
    }
}

@Composable
private fun ParentSetupForm(account: AccountInfo, client: ApiClient, store: LocalControlStore,
                            recovery: Boolean, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    HouseTextField(email, { email = it }, "已关联家长的邮箱", KeyboardType.Email)
    HouseTextField(password, { password = it }, "家长账号的登录密码", KeyboardType.Password, secret = true)
    HouseTextField(pin, { pin = it }, "设置本机家长密码（至少 6 位）", KeyboardType.Password, secret = true)
    HouseTextField(confirmation, { confirmation = it }, "再次输入本机家长密码", KeyboardType.Password, secret = true)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        if (!busy) scope.launch {
            busy = true; error = null
            var guardianToken: String? = null
            try {
                require(pin.length in 6..64 && pin == confirmation) { "请填写至少 6 位且两次一致的本机密码" }
                val parent = client.guardianLogin(email.trim(), password)
                guardianToken = parent.token
                require(client.children(parent.token).any { it.id == account.id }) { "该家长尚未关联这个孩子" }
                if (recovery && store.snapshot().configured) withContext(Dispatchers.IO) { store.changePin(pin) }
                else {
                    val registration = client.registerTimeDevice(parent.token, account.id, UUID.randomUUID().toString(),
                        "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
                    withContext(Dispatchers.IO) { store.configure(account, client.serverAddress, registration, pin) }
                }
                password = ""; pin = ""; confirmation = ""; onSuccess()
            } catch (cancel: CancellationException) { throw cancel }
            catch (ex: Exception) { error = ex.message ?: "本机设置失败" }
            finally {
                withContext(NonCancellable) { withTimeoutOrNull(6_000) {
                    guardianToken?.let { runCatching { client.logout(it) } }
                } }
                busy = false
            }
        }
    }, enabled = !busy) { Text(if (busy) "验证中…" else if (recovery) "验证并重设本机密码" else "验证并设置本机密码") }
}

@Composable
private fun NewPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    HouseDialog("修改家长密码", "锁", "保存密码", onDismiss = onDismiss, onConfirm = {
        if (pin.length !in 6..64 || pin != second) error = "请填写至少 6 位且两次一致的新密码"
        else onSave(pin)
    }) {
        HouseTextField(pin, { pin = it }, "新家长密码", KeyboardType.Password, secret = true)
        HouseTextField(second, { second = it }, "再次输入", KeyboardType.Password, secret = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

package com.pointshome.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RewardGreen = Color(0xFF27685E)
private val RewardMuted = Color(0xFF66807A)
private val RewardRed = Color(0xFFB33B3B)

@Composable
fun RewardsPanel(childId: String, childName: String, guardian: Boolean, token: String,
                 client: ApiClient, revision: Int, onChanged: () -> Unit,
                 historyOnly: Boolean = false, onAppTime: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cached = remember(client, token, childId) { client.cachedRewards(token, childId) }
    var overview by remember(client, token, childId) { mutableStateOf(cached) }
    var history by remember(client, token, childId) {
        mutableStateOf(cached?.redemptions.orEmpty())
    }
    var hasMore by remember(client, token, childId) { mutableStateOf(cached?.hasMore ?: false) }
    var loading by remember(client, token, childId) { mutableStateOf(cached == null) }
    var visibleItems by rememberSaveable(childId) { mutableIntStateOf(10) }
    var loadingMore by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var rewardToRedeem by remember { mutableStateOf<RewardInfo?>(null) }
    var receiptToConfirm by remember { mutableStateOf<RedemptionInfo?>(null) }
    var showCreateReward by rememberSaveable { mutableStateOf(false) }
    val requestIds = remember { mutableMapOf<String, String>() }

    LaunchedEffect(childId, token, revision) {
        client.cachedRewards(token, childId)?.let {
            overview = it
            history = it.redemptions
            hasMore = it.hasMore
        }
        if (client.cachedRewards(token, childId, 15_000L) != null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val latest = client.rewards(token, childId)
            overview = latest
            history = latest.redemptions
            hasMore = latest.hasMore
            error = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ex: Exception) {
            error = ex.message ?: "奖励读取失败"
        } finally {
            loading = false
        }
    }

    fun runRewardAction(success: String, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            notice = null
            try {
                action()
                onSuccess()
                notice = success
                onChanged()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (ex: Exception) {
                error = ex.message ?: "操作失败，请稍后重试"
            } finally {
                busy = false
            }
        }
    }

    rewardToRedeem?.let { reward ->
        HouseDialog(
            title = "确认兑换",
            marker = "兑",
            confirmLabel = if (reward.rewardKind == "APP_TIME") "申请兑换" else "确认兑换",
            cancelLabel = "再想想",
            busy = busy,
            onDismiss = { rewardToRedeem = null },
            onConfirm = {
                rewardToRedeem = null
                runRewardAction(if (reward.rewardKind == "APP_TIME") "已申请，等待家长批准" else "兑换成功，凭据已保存") {
                    val requestId = requestIds.getOrPut(reward.id) { UUID.randomUUID().toString() }
                    val device = if (reward.rewardKind == "APP_TIME") withContext(Dispatchers.IO) {
                        val store = LocalControlStore.get(context)
                        if (!store.matches(childId, client.serverAddress))
                            throw ApiException("请先在菜单的“本机应用限制”中，由家长完成这台手机的设置")
                        val local = store.snapshot()
                        TimeDeviceRegistration(local.deviceId, local.deviceKey)
                    } else null
                    client.redeemReward(token, reward.id, requestId, device)
                    requestIds.remove(reward.id)
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(reward.name, color = HouseInk, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold)
                HouseHint(if (reward.rewardKind == "APP_TIME")
                    "申请 ${reward.timeMinutes} 分钟。家长批准后扣除 ${reward.cost} 分，时间发到申请时绑定的这台手机。"
                    else "将立即使用 ${reward.cost} 分，并保存一张可查看的兑换凭据。")
            }
        }
    }

    receiptToConfirm?.let { receipt ->
        HouseDialog(
            title = "确认已收到",
            marker = "收",
            confirmLabel = "确认收到",
            cancelLabel = "返回",
            busy = busy,
            onDismiss = { receiptToConfirm = null },
            onConfirm = {
                receiptToConfirm = null
                runRewardAction("已记录收到奖励") { client.confirmReward(token, receipt.id) }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(receipt.rewardName, color = HouseInk, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold)
                HouseHint("确认实际收到奖励后再操作，确认时间会保留在兑换凭据中。")
            }
        }
    }

    if (guardian && showCreateReward) {
        CreateRewardDialog(busy, error, onDismiss = { showCreateReward = false }) {
            name, description, cost, kind, minutes ->
            runRewardAction("奖励已添加", onSuccess = { showCreateReward = false }) {
                client.createReward(token, childId, name, description, cost, kind, minutes)
            }
        }
    }

    RewardCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (historyOnly) "兑换凭据" else if (guardian) "${childName}的兑换" else "我的兑换",
                modifier = Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onChanged, enabled = !busy) { Text("刷新") }
        }
        if (!historyOnly) {
            Text(if (loading && overview == null) "正在读取…" else "可用 ${overview?.balance ?: 0} 分",
                color = RewardGreen, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("普通奖励立即扣分；应用时间在家长批准后扣分。", color = RewardMuted, fontSize = 13.sp)
            if (!guardian) TextButton(onClick = onAppTime) { Text("查看应用时间／设置本机限制 →") }
            if (loading && overview != null) Text("正在同步最新数据…", color = RewardMuted, fontSize = 12.sp)
        }
        error?.let { Text(it, color = RewardRed) }
        notice?.let { Text(it, color = RewardGreen) }
    }

    val data = overview ?: return
    val pendingTime = history.filter { it.rewardKind == "APP_TIME" && it.status == "PENDING_APPROVAL" }
    if (!historyOnly && pendingTime.isNotEmpty()) RewardCard {
        Text(if (guardian) "待批准的时间申请" else "我的时间申请", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        pendingTime.take(10).forEach { receipt ->
            TimeReceiptActions(receipt, guardian, busy, onAppTime,
                approve = { runRewardAction("已批准，时间将在孩子手机上领取") { client.provideReward(token, receipt.id) } },
                reject = { runRewardAction("已拒绝，未扣积分") { client.rejectReward(token, receipt.id) } },
                cancel = { runRewardAction("已取消，未扣积分") { client.cancelTimeRequest(token, receipt.id) } })
        }
        if (pendingTime.size > 10) Text("其余申请可在兑换凭据中处理。", color = RewardMuted)
    }
    if (!historyOnly) {
    RewardCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("可兑换奖励", modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (guardian) TextButton(onClick = { showCreateReward = true }, enabled = !busy) {
                Text("＋ 添加")
            }
        }
        if (data.items.isEmpty()) Text("还没有奖励项目。", color = RewardMuted)
        data.items.take(visibleItems).forEach { reward ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(reward.name, fontWeight = FontWeight.SemiBold)
                reward.description?.let { Text(it, color = RewardMuted, fontSize = 13.sp) }
                Text("${reward.cost} 分${if (reward.active) "" else " · 已停用"}",
                    color = RewardMuted, fontSize = 13.sp)
                if (reward.rewardKind == "APP_TIME") Text("限制应用使用时间 · ${reward.timeMinutes} 分钟 · 需家长批准",
                    color = RewardGreen, fontSize = 13.sp)
                if (guardian) {
                    OutlinedButton(onClick = {
                        runRewardAction(if (reward.active) "已停用奖励" else "已恢复奖励") {
                            if (reward.active) client.archiveReward(token, reward.id)
                            else client.restoreReward(token, reward.id)
                        }
                    }, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                        Text(if (reward.active) "停止兑换" else "恢复兑换")
                    }
                } else {
                    Button(onClick = { rewardToRedeem = reward },
                        enabled = !busy && reward.active && data.balance >= reward.cost,
                        shape = RoundedCornerShape(14.dp)) {
                        Text(if (data.balance < reward.cost) "积分不足" else if (reward.rewardKind == "APP_TIME") "申请兑换时间" else "兑换奖励")
                    }
                }
            }
        }
        if (data.items.size > visibleItems) TextButton(onClick = { visibleItems += 10 }) {
            Text("查看更多奖励（剩余 ${data.items.size - visibleItems} 项）")
        }
    }
    }

    if (historyOnly) {
    RewardCard {
        Text("兑换凭据", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("已兑换的项目不会因奖励停用而消失。", color = RewardMuted, fontSize = 13.sp)
        if (history.isEmpty()) Text("还没有兑换记录。", color = RewardMuted)
        history.forEach { receipt ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${receipt.rewardName} · ${receipt.cost} 分",
                    fontWeight = FontWeight.SemiBold)
                receipt.rewardDescription?.let { Text(it, color = RewardMuted, fontSize = 13.sp) }
                Text("${if (receipt.rewardKind == "APP_TIME" && receipt.status == "RECEIVED") "时间已到账" else rewardStatus(receipt.status)} · ${rewardDate(receipt.requestedAt)}",
                    color = RewardMuted, fontSize = 13.sp)
                SelectionContainer {
                    Text("凭据编号：${receipt.id}", color = RewardMuted, fontSize = 11.sp)
                }
                if (guardian && receipt.status == "REQUESTED") {
                    OutlinedButton(onClick = {
                        runRewardAction("已标记提供，等待孩子确认") {
                            client.provideReward(token, receipt.id)
                        }
                    }, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                        Text("标记已提供")
                    }
                }
                if (receipt.rewardKind == "APP_TIME") TimeReceiptActions(receipt, guardian, busy, onAppTime,
                    approve = { runRewardAction("已批准，时间将在孩子手机上领取") { client.provideReward(token, receipt.id) } },
                    reject = { runRewardAction("已拒绝，未扣积分") { client.rejectReward(token, receipt.id) } },
                    cancel = { runRewardAction("已取消，未扣积分") { client.cancelTimeRequest(token, receipt.id) } })
                if (!guardian && receipt.rewardKind == "STANDARD" && receipt.status != "RECEIVED") {
                    OutlinedButton(onClick = { receiptToConfirm = receipt }, enabled = !busy,
                        shape = RoundedCornerShape(14.dp)) {
                        Text("确认已收到")
                    }
                }
            }
        }
        if (hasMore) {
            TextButton(onClick = {
                if (loadingMore) return@TextButton
                scope.launch {
                    loadingMore = true
                    try {
                        val page = client.redemptionHistory(token, childId, history.size)
                        history = history + page.entries.filter { next -> history.none { it.id == next.id } }
                        hasMore = page.hasMore
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (ex: Exception) {
                        error = ex.message ?: "读取旧凭据失败"
                    } finally {
                        loadingMore = false
                    }
                }
            }, enabled = !busy && !loadingMore) {
                Text(if (loadingMore) "读取中…" else "查看更多凭据")
            }
        }
    }
    }
}

@Composable
private fun CreateRewardDialog(busy: Boolean, apiError: String?, onDismiss: () -> Unit,
                               onCreate: (String, String, Int, String, Int?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("STANDARD") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    HouseDialog(
        title = "添加兑换奖励",
        subtitle = "给努力一个值得期待的目标",
        marker = "礼",
        confirmLabel = "添加奖励",
        busy = busy,
        onDismiss = onDismiss,
        onConfirm = {
            val amount = cost.toIntOrNull()
            if (name.isBlank() || name.length > 80 || description.length > 300 ||
                amount == null || amount !in 1..100000) {
                error = "请填写名称和 1～100000 之间的积分"
            } else {
                error = null
                val duration = minutes.toIntOrNull()
                if (kind == "APP_TIME" && (duration == null || duration !in 1..1440)) error = "时间请填写 1～1440 分钟"
                else onCreate(name.trim(), description.trim(), amount, kind, if (kind == "APP_TIME") duration else null)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("STANDARD" to "普通奖励", "APP_TIME" to "应用时间").forEach { (id, label) ->
                    OutlinedButton(onClick = {
                        kind = id
                        if (id == "APP_TIME" && name.isBlank()) name = "限制应用使用时间"
                    }, modifier = Modifier.weight(1f)) { Text(if (kind == id) "✓ $label" else label) }
                }
            }
            HouseTextField(name, { name = it }, "奖励名称")
            HouseTextField(description, { description = it }, "说明（可选）")
            HouseTextField(cost, { cost = it }, "需要的积分", KeyboardType.Number)
            if (kind == "APP_TIME") HouseTextField(minutes, { minutes = it }, "发放的时间（分钟）", KeyboardType.Number)
            HouseHint(if (kind == "APP_TIME") "家长批准后才扣积分和发时间。所有受限应用共用该时间余额。"
                else "孩子兑换后会生成凭据，方便双方核对。")
            error?.let { Text(it, color = RewardRed, fontSize = 13.sp) }
            apiError?.let { Text(it, color = RewardRed, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun TimeReceiptActions(receipt: RedemptionInfo, guardian: Boolean, busy: Boolean,
                               onAppTime: () -> Unit, approve: () -> Unit,
                               reject: () -> Unit, cancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${receipt.rewardName} · ${receipt.timeMinutes} 分钟 · ${receipt.cost} 分", fontWeight = FontWeight.Medium)
        if (receipt.status == "PENDING_APPROVAL") {
            Text("等待家长批准，尚未扣分", color = RewardMuted, fontSize = 13.sp)
            if (guardian) {
                Button(onClick = approve, enabled = !busy) { Text("批准 · 扣 ${receipt.cost} 分") }
                OutlinedButton(onClick = reject, enabled = !busy) { Text("拒绝申请") }
            } else OutlinedButton(onClick = cancel, enabled = !busy) { Text("取消申请") }
        } else if (!guardian && receipt.status == "APPROVED") {
            TextButton(onClick = onAppTime) { Text("领取时间到本机 →") }
        }
    }
}

@Composable
private fun RewardCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

private fun rewardStatus(status: String): String = when (status) {
    "REQUESTED" -> "待家长兑现"
    "OFFERED" -> "家长已标记提供，待孩子确认"
    "RECEIVED" -> "孩子已确认收到"
    "PENDING_APPROVAL" -> "待家长批准（未扣分）"
    "APPROVED" -> "已批准，待手机领取"
    "REJECTED" -> "家长已拒绝（未扣分）"
    "CANCELLED" -> "孩子已取消（未扣分）"
    else -> status
}

private fun rewardDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

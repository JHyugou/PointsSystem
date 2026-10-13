package com.pointshome.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
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

private val PointGreen = Color(0xFF27685E)
private val PointMuted = Color(0xFF66807A)
private val PointRed = Color(0xFFB33B3B)

@Composable
fun GuardianDashboard(children: List<FamilyMember>, token: String, client: ApiClient,
                      revision: Int, page: String, section: String, selectedChildId: String?,
                      onSection: (String) -> Unit, onSelectChild: (String) -> Unit,
                      onFamily: () -> Unit, onChanged: () -> Unit) {
    if (children.isEmpty()) {
        PointCard {
            Text("还没有孩子账号", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("先创建或关联孩子，再记录积分。", color = PointMuted)
            Button(onClick = onFamily, shape = RoundedCornerShape(14.dp)) { Text("前往家庭管理") }
        }
        return
    }
    val child = children.find { it.id == selectedChildId } ?: children.first()
    if (children.size > 1) {
        PointCard {
            Text("选择孩子", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                children.forEach { member ->
                    if (member.id == child.id) {
                        Button(onClick = { onSelectChild(member.id) }, shape = RoundedCornerShape(14.dp)) {
                            Text(member.displayName)
                        }
                    } else {
                        OutlinedButton(onClick = { onSelectChild(member.id) }, shape = RoundedCornerShape(14.dp)) {
                            Text(member.displayName)
                        }
                    }
                }
            }
        }
    }
    if (page == "home") {
        var openedPoints by remember(child.id) { mutableStateOf(section == "points") }
        var openedRewards by remember(child.id) { mutableStateOf(section == "rewards") }
        var pointsRevision by remember(child.id) { mutableIntStateOf(revision) }
        var rewardsRevision by remember(child.id) { mutableIntStateOf(revision) }
        DashboardTabs(section) { next ->
            if (next == "points") {
                openedPoints = true
                val fresh = client.cachedPoints(token, child.id, 15_000L) != null
                pointsRevision = maxOf(pointsRevision, revision) + if (fresh) 0 else 1
            } else {
                openedRewards = true
                val fresh = client.cachedRewards(token, child.id, 15_000L) != null
                rewardsRevision = maxOf(rewardsRevision, revision) + if (fresh) 0 else 1
            }
            onSection(next)
        }
        key(child.id) {
            RetainedDashboardPanels(section,
                points = {
                    if (openedPoints) PointsPanel(child.id, child.displayName, true, token,
                        client, pointsRevision, onChanged = {
                            pointsRevision++
                            onChanged()
                        })
                },
                rewards = {
                    if (openedRewards) RewardsPanel(child.id, child.displayName, true, token,
                        client, rewardsRevision, onChanged = {
                            rewardsRevision++
                            onChanged()
                        })
                })
        }
    } else key(child.id, page) {
        when (page) {
            "history" -> PointsPanel(child.id, child.displayName, true, token, client,
                revision, onChanged, historyOnly = true)
            "redemptions" -> RewardsPanel(child.id, child.displayName, true, token, client,
                revision, onChanged, historyOnly = true)
        }
    }
}

@Composable
fun ChildDashboard(childId: String, token: String, client: ApiClient,
                   revision: Int, page: String, section: String,
                   onSection: (String) -> Unit, onAppTime: () -> Unit, onChanged: () -> Unit) {
    if (page == "home") {
        var openedPoints by remember(childId) { mutableStateOf(section == "points") }
        var openedRewards by remember(childId) { mutableStateOf(section == "rewards") }
        var pointsRevision by remember(childId) { mutableIntStateOf(revision) }
        var rewardsRevision by remember(childId) { mutableIntStateOf(revision) }
        DashboardTabs(section) { next ->
            if (next == "points") {
                openedPoints = true
                val fresh = client.cachedPoints(token, childId, 15_000L) != null
                pointsRevision = maxOf(pointsRevision, revision) + if (fresh) 0 else 1
            } else {
                openedRewards = true
                val fresh = client.cachedRewards(token, childId, 15_000L) != null
                rewardsRevision = maxOf(rewardsRevision, revision) + if (fresh) 0 else 1
            }
            onSection(next)
        }
        key(childId) {
            RetainedDashboardPanels(section,
                points = {
                    if (openedPoints) PointsPanel(childId, "我的", false, token, client,
                        pointsRevision, onChanged = {
                            pointsRevision++
                            onChanged()
                        })
                },
                rewards = {
                    if (openedRewards) RewardsPanel(childId, "我的", false, token, client,
                        rewardsRevision, onChanged = {
                            rewardsRevision++
                            onChanged()
                        }, onAppTime = onAppTime)
                })
        }
    } else when (page) {
        "history" -> PointsPanel(childId, "我的", false, token, client,
            revision, onChanged, historyOnly = true)
        "redemptions" -> RewardsPanel(childId, "我的", false, token, client,
            revision, onChanged, historyOnly = true, onAppTime = onAppTime)
    }
}

@Composable
private fun RetainedDashboardPanels(section: String, points: @Composable () -> Unit,
                                    rewards: @Composable () -> Unit) {
    Layout(content = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) { points() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) { rewards() }
    }) { measurables, constraints ->
        // Keep both visited tabs composed; lay out only the visible tab.
        val placeable = measurables[if (section == "points") 0 else 1].measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

@Composable
private fun DashboardTabs(section: String, onSelect: (String) -> Unit) {
    PointCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("points" to "积分", "rewards" to "兑换").forEach { (id, label) ->
                val selected = section == id
                OutlinedButton(onClick = { onSelect(id) }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) PointGreen else Color.White,
                        contentColor = if (selected) Color.White else PointGreen),
                    border = BorderStroke(1.dp, if (selected) PointGreen else PointMuted)) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun PointsPanel(childId: String, childName: String, guardian: Boolean,
                        token: String, client: ApiClient, revision: Int, onChanged: () -> Unit,
                        historyOnly: Boolean = false) {
    val scope = rememberCoroutineScope()
    var overview by remember(client, token, childId) {
        mutableStateOf(client.cachedPoints(token, childId))
    }
    var loading by remember(client, token, childId) { mutableStateOf(overview == null) }
    var visibleItems by rememberSaveable(childId) { mutableIntStateOf(10) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showCreateItem by rememberSaveable { mutableStateOf(false) }
    val requestIds = remember { mutableMapOf<String, String>() }

    LaunchedEffect(childId, token, revision) {
        client.cachedPoints(token, childId)?.let { overview = it }
        if (client.cachedPoints(token, childId, 15_000L) != null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            overview = client.points(token, childId)
            error = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ex: ApiException) {
            error = ex.message
        } finally {
            loading = false
        }
    }

    fun runPointAction(success: String, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
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
            } catch (ex: Exception) {
                error = ex.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    if (guardian && showCreateItem) {
        CreateItemDialog(busy, error, onDismiss = { showCreateItem = false }) { name, points, uses, days ->
            runPointAction("积分项目已创建", onSuccess = { showCreateItem = false }) {
                client.addPointItem(token, childId, name, points, uses, days)
            }
        }
    }

    PointCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (historyOnly) {
                if (guardian) "${childName}的积分记录" else "我的积分记录"
            } else {
                if (guardian) "${childName}的积分" else "我的积分"
            },
                modifier = Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onChanged, enabled = !busy) { Text("刷新") }
        }
        if (!historyOnly) {
            Text(if (loading && overview == null) "正在读取…" else "${overview?.balance ?: 0} 分",
                color = PointGreen, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (loading && overview != null) Text("正在同步最新数据…", color = PointMuted, fontSize = 12.sp)
            Text("负数项目在执行 30 分钟后生效，期间可取消。",
                color = PointMuted, fontSize = 13.sp)
        }
        error?.let { Text(it, color = PointRed) }
        notice?.let { Text(it, color = PointGreen) }
    }

    val data = overview ?: return
    if (!historyOnly) {
    PointCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("积分项目", modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (guardian) TextButton(onClick = { showCreateItem = true }, enabled = !busy) {
                Text("＋ 添加")
            }
        }
        if (data.items.isEmpty()) Text("还没有积分项目。", color = PointMuted)
        data.items.take(visibleItems).forEach { item ->
            val usable = (item.remainingUses == null || item.remainingUses > 0) &&
                (item.expiresAt == null || item.expiresAt > System.currentTimeMillis())
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                Text("${signed(item.points)} 分 · " +
                    (item.remainingUses?.let { "剩余 $it 次" } ?: "次数不限") + " · " +
                    (item.expiresAt?.let { "${date(it)} 截止" } ?: "永久有效"),
                    color = PointMuted, fontSize = 13.sp)
                if (guardian) {
                    OutlinedButton(onClick = {
                        runPointAction(if (item.points < 0) "扣分已提交，30 分钟后生效" else "积分已发放") {
                            val key = "grant:${item.id}"
                            val requestId = requestIds.getOrPut(key) { UUID.randomUUID().toString() }
                            client.grantPoints(token, item.id, requestId)
                            requestIds.remove(key)
                        }
                    }, enabled = usable && !busy, shape = RoundedCornerShape(14.dp)) {
                        Text(if (item.points < 0) "扣除 ${-item.points} 分" else "发放 ${item.points} 分")
                    }
                } else if (item.points > 0) {
                    val pending = data.claims.any { it.itemId == item.id && it.status == "PENDING" }
                    OutlinedButton(onClick = {
                        runPointAction("已提交申报，等待家长审批") { client.claimPoints(token, item.id) }
                    }, enabled = usable && !pending && !busy, shape = RoundedCornerShape(14.dp)) {
                        Text(if (pending) "等待审批" else "申报完成")
                    }
                }
            }
        }
        if (data.items.size > visibleItems) TextButton(onClick = { visibleItems += 10 }) {
            Text("查看更多项目（剩余 ${data.items.size - visibleItems} 项）")
        }
    }
    }

    val visibleClaims = if (historyOnly) data.claims.take(20)
        else data.claims.filter { it.status == "PENDING" }
    if (historyOnly || visibleClaims.isNotEmpty()) PointCard {
        Text(if (historyOnly) "申报记录" else if (guardian) "待审批申报" else "我的待审申报",
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (visibleClaims.isEmpty()) Text("暂无申报。", color = PointMuted)
        visibleClaims.forEach { claim ->
            val name = data.items.find { it.id == claim.itemId }?.name ?: "积分项目"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$name · ${claimLabel(claim.status)}", fontWeight = FontWeight.Medium)
                Text(date(claim.createdAt), color = PointMuted, fontSize = 12.sp)
                if (guardian && claim.status == "PENDING") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { runPointAction("已通过申报") {
                            client.approveClaim(token, claim.id)
                        } }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("通过") }
                        OutlinedButton(onClick = { runPointAction("已拒绝申报") {
                            client.rejectClaim(token, claim.id)
                        } }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("拒绝") }
                    }
                }
            }
        }
    }

    if (historyOnly) {
    PointCard {
        Text("操作记录", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (data.actions.isEmpty()) Text("还没有积分操作。", color = PointMuted)
        data.actions.take(20).forEach { action ->
            val name = data.items.find { it.id == action.itemId }?.name
                ?: when (action.kind) {
                    "DIRECT_DEDUCTION" -> "直接扣分"
                    "REWARD_REDEMPTION" -> "奖励兑换"
                    else -> "积分项目"
                }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$name · ${signed(action.amount)} 分 · ${actionLabel(action.status)}",
                    fontWeight = FontWeight.Medium)
                action.note?.let { Text(it, color = PointMuted, fontSize = 13.sp) }
                Text(if (action.status == "PENDING" && action.dueAt != null)
                    "预计 ${date(action.dueAt)} 生效" else date(action.createdAt),
                    color = PointMuted, fontSize = 12.sp)
                if (guardian && action.kind != "REWARD_REDEMPTION" &&
                    action.status in listOf("PENDING", "APPLIED")) {
                    OutlinedButton(onClick = { runPointAction("操作已撤销") {
                        client.reversePoints(token, action.id)
                    } }, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                        Text(if (action.status == "PENDING") "取消待生效扣分" else "撤销这次积分")
                    }
                }
            }
        }
    }

    PointCard {
        Text("积分流水", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (data.ledger.isEmpty()) Text("暂无已生效的积分变动。", color = PointMuted)
        data.ledger.take(20).forEach { entry ->
            val action = data.actions.find { it.id == entry.actionId }
            val label = when {
                entry.kind == "REVERSAL" -> "撤销冲正"
                action?.kind == "REWARD_REDEMPTION" -> "兑换奖励"
                else -> "积分生效"
            }
            Text("${signed(entry.delta)} 分 · $label · ${date(entry.createdAt)}",
                color = if (entry.delta < 0) PointRed else PointGreen, fontSize = 13.sp)
        }
    }
    }
}

@Composable
private fun CreateItemDialog(busy: Boolean, apiError: String?, onDismiss: () -> Unit,
                             onCreate: (String, Int, Int?, Int?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var points by rememberSaveable { mutableStateOf("") }
    var uses by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    HouseDialog(
        title = "添加积分项目",
        subtitle = "设定获得或扣除积分的规则",
        marker = "＋",
        confirmLabel = "创建项目",
        busy = busy,
        onDismiss = onDismiss,
        onConfirm = {
            val value = points.toIntOrNull()
            val count = if (uses.isBlank()) null else uses.toIntOrNull()
            val duration = if (days.isBlank()) null else days.toIntOrNull()
            if (name.isBlank() || value == null || value == 0 || value !in -100000..100000
                || (uses.isNotBlank() && (count == null || count !in 1..10000))
                || (days.isNotBlank() && (duration == null || duration !in 1..3650))) {
                error = "请填写项目名称和有效的积分、次数、天数"
            } else {
                error = null
                onCreate(name.trim(), value, count, duration)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HouseTextField(name, { name = it }, "项目名称")
            HouseTextField(points, { points = it }, "积分（扣分填写负数）")
            HouseTextField(uses, { uses = it }, "可完成次数（可留空）", KeyboardType.Number)
            HouseTextField(days, { days = it }, "有效天数（可留空）", KeyboardType.Number)
            HouseHint("填写负数即可扣分，提交后 30 分钟生效。")
            error?.let { Text(it, color = PointRed, fontSize = 13.sp) }
            apiError?.let { Text(it, color = PointRed, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun PointCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
private fun date(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
private fun claimLabel(status: String): String = when (status) {
    "PENDING" -> "待审批"
    "APPROVED" -> "已通过"
    "REJECTED" -> "已拒绝"
    else -> status
}
private fun actionLabel(status: String): String = when (status) {
    "PENDING" -> "待生效"
    "APPLIED" -> "已生效"
    "CANCELLED" -> "已取消"
    "REVERSED" -> "已撤销"
    else -> status
}

package com.pointshome.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Pine = Color(0xFF27685E)
private val Ink = Color(0xFF213B38)
private val Mist = Color(0xFFF4F8F5)
private val Muted = Color(0xFF66807A)

class MainActivity : ComponentActivity() {
    private var requestedPage by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        requestedPage = intent.getStringExtra("open_page")
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Pine, onPrimary = Color.White,
                    background = Mist, onBackground = Ink,
                    surface = Color.White, onSurface = Ink,
                    surfaceVariant = HouseMist, onSurfaceVariant = HouseMuted,
                    outline = HouseLine, outlineVariant = HouseLine,
                    secondary = Pine, secondaryContainer = HouseSoftGreen, onSecondaryContainer = Ink,
                )
            ) { PointsHome(requestedPage) { requestedPage = null } }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPage = intent.getStringExtra("open_page")
    }
}

@Composable
private fun PointsHome(requestedPage: String?, onPageConsumed: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("server_settings", Context.MODE_PRIVATE) }
    val vault = remember { TokenVault(context) }
    var serverUrl by rememberSaveable {
        mutableStateOf(settings.getString("server_url", BuildConfig.DEFAULT_API_URL) ?: BuildConfig.DEFAULT_API_URL)
    }
    val client = remember(serverUrl) { ApiClient(serverUrl) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var children by remember { mutableStateOf(emptyList<FamilyMember>()) }
    var guardians by remember { mutableStateOf(emptyList<FamilyMember>()) }
    var invite by remember { mutableStateOf<InviteInfo?>(null) }
    var dataRevision by remember { mutableIntStateOf(0) }
    var resetCompleted by remember { mutableIntStateOf(0) }
    var childResetCompleted by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("home") }
    var homeSection by rememberSaveable { mutableStateOf("points") }
    var selectedChildId by rememberSaveable { mutableStateOf<String?>(null) }
    val pageScroll = rememberScrollState()
    LaunchedEffect(page) { pageScroll.scrollTo(0) }
    val control by produceState<LocalControlStore?>(null) {
        value = withContext(Dispatchers.IO) { LocalControlStore.get(context) }
    }
    var guardedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var recoverControlPin by remember { mutableStateOf(false) }

    fun protectAction(action: () -> Unit) {
        if (control?.snapshot()?.configured == true) guardedAction = action else action()
    }

    guardedAction?.let { pending -> control?.let { store ->
        ParentPinDialog(store, onDismiss = { guardedAction = null }, onConfirmed = {
            guardedAction = null; pending()
        }, onRecovery = { guardedAction = null; recoverControlPin = true })
    } }
    if (recoverControlPin) control?.let { store ->
        val bound = store.snapshot()
        ParentSetupDialog(AccountInfo(bound.childId, "CHILD", null, bound.username, bound.childName),
            remember(bound.serverUrl) { ApiClient(bound.serverUrl) }, store, recovery = true,
            onDismiss = { recoverControlPin = false }, onSuccess = {
                recoverControlPin = false; notice = "家长密码已重设，请重新进行原操作"
            })
    }

    LaunchedEffect(Unit) {
        val local = withContext(Dispatchers.IO) { LocalControlStore.get(context) }
        withContext(Dispatchers.IO) { vault.read() }?.let { saved ->
            try {
                val restored = client.me(saved)
                token = saved
                account = restored
            } catch (ex: Exception) {
                val cached = local.snapshot()
                if ((ex as? ApiException)?.status == 401) {
                    withContext(Dispatchers.IO) { vault.clear() }
                    error = "登录已失效，请由家长重新登录；已到账的本机时间仍可使用"
                } else {
                    token = saved
                    error = "暂时无法联网；已到账的本机时间仍可使用"
                }
                if (local.matches(cached.childId, serverUrl)) {
                    account = AccountInfo(cached.childId, "CHILD", null, cached.username, cached.childName)
                    page = "app_time"
                }
            }
        }
        connected = client.health()
        loading = false
    }

    LaunchedEffect(requestedPage, loading, account?.id) {
        if (!loading && requestedPage == "app_time" && account?.role == "CHILD") {
            page = "app_time"; onPageConsumed()
        }
    }

    if (account?.role == "CHILD" && token != null) control?.let {
        TimeGrantSync(account!!, token!!, client, it) { client.clearOverviewCache(); dataRevision++ }
    }

    LaunchedEffect(account?.id, serverUrl) {
        val current = account ?: return@LaunchedEffect
        val activeToken = token ?: return@LaunchedEffect
        try {
            if (current.role == "GUARDIAN") children = client.children(activeToken)
            else guardians = client.guardians(activeToken)
        } catch (ex: ApiException) {
            error = ex.message
        }
    }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            notice = null
            try {
                action()
            } catch (ex: Exception) {
                error = ex.message ?: "操作失败，请稍后再试"
            } finally {
                busy = false
            }
        }
    }

    suspend fun saveSession(session: LoginSession) {
        withContext(Dispatchers.IO) { vault.save(session.token) }
        client.clearOverviewCache()
        token = session.token
        account = session.account
        invite = null
        page = "home"
        notice = "欢迎，${session.account.displayName}"
    }

    fun acceptSession(session: LoginSession) {
        val bound = control?.snapshot()
        val action = { scope.launch {
            try { saveSession(session) } catch (ex: Exception) { error = ex.message ?: "登录信息保存失败" }
        }; Unit }
        if (bound?.configured == true && (session.account.role != "CHILD" || session.account.id != bound.childId))
            protectAction(action)
        else action()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Mist) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.TopCenter) {
        val sidePadding = if (maxWidth < 360.dp) 12.dp else if (maxWidth < 420.dp) 16.dp else 20.dp
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(pageScroll)
                .padding(horizontal = sidePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HomeHeader(page, account, connected, busy, onPage = { page = it }, onLogout = { protectAction {
                runAction {
                    token?.let { runCatching { client.logout(it) } }
                    withContext(Dispatchers.IO) { vault.clear() }
                    client.clearOverviewCache()
                    token = null
                    account = null
                    children = emptyList()
                    guardians = emptyList()
                    invite = null
                    page = "home"
                    notice = "已退出登录"
                }
            } })

            error?.let { Feedback(it, Color(0xFFB33B3B)) }
            notice?.let { Feedback(it, Pine) }

            if (loading) {
                CircularProgressIndicator()
            } else if (page == "server") {
                ServerPanel(serverUrl, connected, busy,
                    onChange = { newUrl -> protectAction {
                        runAction {
                        if (newUrl != serverUrl) {
                            withContext(Dispatchers.IO) { vault.clear() }
                            client.clearOverviewCache()
                            token = null
                            account = null
                            children = emptyList()
                            guardians = emptyList()
                            invite = null
                            connected = null
                            serverUrl = newUrl
                            settings.edit().putString("server_url", newUrl).apply()
                        }
                        }
                    } },
                    onCheck = { runAction {
                        connected = client.health()
                        if (connected == false) error = "暂时无法连接，请检查后端地址"
                    } },
                )
            } else {
                if (account == null) {
                    AuthPanel(
                        busy = busy,
                        resetCompleted = resetCompleted,
                        onRequestCode = { email -> runAction {
                            client.requestCode(email)
                            notice = "如邮箱尚未注册，请查收验证码；控制台模式请查看后端窗口"
                        } },
                        onRequestResetCode = { email -> runAction {
                            client.requestPasswordResetCode(email)
                            notice = "如该邮箱已注册，请查收验证码；控制台模式请查看后端窗口"
                        } },
                        onResetPassword = { email, code, newPassword -> runAction {
                            client.resetGuardianPassword(email, code, newPassword)
                            resetCompleted++
                            notice = "密码已重置，请用新密码登录"
                        } },
                        onRegister = { email, code, password, name -> runAction {
                            acceptSession(client.registerGuardian(email, code, password, name))
                        } },
                        onGuardianLogin = { email, password -> runAction {
                            acceptSession(client.guardianLogin(email, password))
                        } },
                        onChildLogin = { username, password -> runAction {
                            acceptSession(client.childLogin(username, password))
                        } },
                    )
                } else {
                    if (account!!.role == "GUARDIAN") {
                        if (page == "family") {
                            GuardianPanel(children, invite, busy, error, childResetCompleted,
                                onCreateChild = { username, password, name -> runAction {
                                    val activeToken = token ?: return@runAction
                                    client.createChild(activeToken, username, password, name)
                                    children = client.children(activeToken)
                                    notice = "孩子账号已创建"
                                } },
                                onInvite = { childId -> runAction {
                                    val activeToken = token ?: return@runAction
                                    invite = client.invite(activeToken, childId)
                                } },
                                onRedeem = { code -> runAction {
                                    val activeToken = token ?: return@runAction
                                    client.redeem(activeToken, code)
                                    children = client.children(activeToken)
                                    notice = "已关联孩子"
                                } },
                                onResetChildPassword = { childId, newPassword -> runAction {
                                    val activeToken = token ?: return@runAction
                                    client.resetChildPassword(activeToken, childId, newPassword)
                                    childResetCompleted++
                                    notice = "孩子密码已重置，请让孩子用新密码登录"
                                } },
                            )
                        } else if (page == "ai") {
                            token?.let { AiSettingsPanel(client, it) }
                        } else if (page == "homework") {
                            token?.let {
                                HomeworkPanel(children, selectedChildId,
                                    onSelectChild = { childId -> selectedChildId = childId },
                                    token = it, client = client,
                                    onAiSettings = { page = "ai" },
                                    onPointsChanged = { client.clearOverviewCache(); dataRevision++ })
                            }
                        } else {
                            token?.let {
                                GuardianDashboard(children, it, client, dataRevision, page,
                                    homeSection, selectedChildId,
                                    onSection = { homeSection = it },
                                    onSelectChild = { selectedChildId = it },
                                    onFamily = { page = "family" },
                                    onChanged = { client.clearOverviewCache(); dataRevision++ })
                            }
                        }
                    } else {
                        if (page == "family") ChildPanel(guardians)
                        else if (page == "app_time") control?.let {
                            LocalControlPanel(account!!, token.orEmpty(), client, it,
                                onRewards = { page = "home"; homeSection = "rewards" })
                        }
                        else token?.let {
                            if (page == "home") control?.let { store ->
                                if (store.matches(account!!.id, serverUrl)) TimeBalanceCard(store) { page = "app_time" }
                            }
                            ChildDashboard(account!!.id, it, client, dataRevision, page,
                                homeSection, onSection = { homeSection = it },
                                onAppTime = { page = "app_time" },
                                onChanged = { client.clearOverviewCache(); dataRevision++ })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun HomeHeader(page: String, account: AccountInfo?, connected: Boolean?, busy: Boolean,
                       onPage: (String) -> Unit, onLogout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val title = when (page) {
        "family" -> if (account?.role == "GUARDIAN") "家庭管理" else "我的家人"
        "history" -> "积分记录"
        "redemptions" -> "兑换凭据"
        "ai" -> "AI 设置"
        "homework" -> "作业助手"
        "server" -> "服务器连接"
        "app_time" -> "应用使用时间"
        else -> "积分小屋"
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Ink)
            if (page == "home") {
                Text(if (account == null) "和家人一起记录努力，兑换快乐。"
                     else "你好，${account.displayName}", color = Muted)
            }
        }
        Box {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.background(Color.White, RoundedCornerShape(14.dp))
                    .semantics { contentDescription = "打开菜单" },
            ) {
                Text("☰", color = Pine, fontSize = 24.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color.White).padding(horizontal = 6.dp),
                containerColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
            ) {
                Text("页面导航", modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 5.dp),
                    color = Muted, fontSize = 12.sp)
                HouseMenuItem("首页", page == "home") {
                    expanded = false; onPage("home")
                }
                if (account != null) {
                    HouseMenuItem(if (account.role == "GUARDIAN") "家庭管理" else "我的家人",
                        page == "family") { expanded = false; onPage("family") }
                    HouseMenuItem("积分记录", page == "history") {
                        expanded = false; onPage("history")
                    }
                    HouseMenuItem("兑换凭据", page == "redemptions") {
                        expanded = false; onPage("redemptions")
                    }
                    if (account.role == "GUARDIAN") {
                        HouseMenuItem("作业助手", page == "homework") {
                            expanded = false; onPage("homework")
                        }
                        HouseMenuItem("AI 设置", page == "ai") {
                            expanded = false; onPage("ai")
                        }
                    } else {
                        HouseMenuItem("本机应用限制", page == "app_time") {
                            expanded = false; onPage("app_time")
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = HouseLine)
                HouseMenuItem("服务器连接", page == "server",
                    trailing = if (connected == true) "在线" else null) {
                    expanded = false; onPage("server")
                }
                if (account != null) {
                    HouseMenuItem("退出登录", false, enabled = !busy, danger = true) {
                        expanded = false; onLogout()
                    }
                }
            }
        }
    }
    if (page != "home") {
        TextButton(onClick = { onPage("home") }) { Text("← 返回首页") }
    } else if (account == null) {
        Text("服务器：" + if (connected == true) "已连接" else "尚未连接",
            color = if (connected == true) Pine else Muted, fontSize = 13.sp)
    }
}

@Composable
private fun HouseMenuItem(
    label: String,
    selected: Boolean,
    trailing: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(label, color = when {
                danger -> Color(0xFF9D4A43)
                selected -> Pine
                else -> Ink
            }, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 15.sp)
        },
        onClick = onClick,
        enabled = enabled,
        trailingIcon = {
            if (trailing != null) Text(trailing, color = Pine, fontSize = 11.sp)
            else if (selected) Text("●", color = Pine, fontSize = 10.sp)
        },
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) HouseSoftGreen else Color.Transparent),
    )
}

@Composable
private fun AuthPanel(
    busy: Boolean,
    resetCompleted: Int,
    onRequestCode: (String) -> Unit,
    onRequestResetCode: (String) -> Unit,
    onResetPassword: (String, String, String) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onGuardianLogin: (String, String) -> Unit,
    onChildLogin: (String, String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("guardian-login") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var resetCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(resetCompleted) {
        if (resetCompleted > 0) {
            mode = "guardian-login"
            password = ""
            resetCode = ""
            newPassword = ""
        }
    }

    RoundedCard {
        Text("开始使用", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { mode = "guardian-login" }, modifier = Modifier.weight(1f)) {
                Text("家长登录", color = if (mode == "guardian-login") Pine else Muted)
            }
            TextButton(onClick = { mode = "guardian-register" }, modifier = Modifier.weight(1f)) {
                Text("家长注册", color = if (mode == "guardian-register") Pine else Muted)
            }
            TextButton(onClick = { mode = "child-login" }, modifier = Modifier.weight(1f)) {
                Text("孩子登录", color = if (mode == "child-login") Pine else Muted)
            }
        }

        when (mode) {
            "guardian-login" -> {
                FormField(email, { email = it }, "邮箱")
                FormField(password, { password = it }, "密码", secret = true)
                Button(onClick = { onGuardianLogin(email, password) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("登录")
                }
                TextButton(onClick = { mode = "guardian-reset" }) { Text("忘记密码？") }
            }
            "guardian-register" -> {
                Text("支持 QQ、163 等常用邮箱", color = Muted, fontSize = 13.sp)
                FormField(name, { name = it }, "称呼")
                FormField(email, { email = it }, "邮箱")
                OutlinedButton(onClick = { onRequestCode(email) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("获取邮箱验证码")
                }
                FormField(code, { code = it }, "6 位验证码")
                FormField(password, { password = it }, "密码（至少 10 位）", secret = true)
                Button(onClick = { onRegister(email, code, password, name) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("注册家长账号")
                }
            }
            "guardian-reset" -> {
                Text("用注册邮箱接收验证码并设置新密码。", color = Muted, fontSize = 13.sp)
                FormField(email, { email = it }, "注册邮箱")
                OutlinedButton(onClick = { onRequestResetCode(email) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("获取找回密码验证码")
                }
                FormField(resetCode, { resetCode = it }, "6 位验证码")
                FormField(newPassword, { newPassword = it }, "新密码（至少 10 位）", secret = true)
                Button(onClick = { onResetPassword(email, resetCode, newPassword) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("重置密码")
                }
                TextButton(onClick = { mode = "guardian-login" }) { Text("返回家长登录") }
            }
            else -> {
                Text("孩子账号由家长创建。", color = Muted, fontSize = 13.sp)
                FormField(username, { username = it }, "孩子账号名")
                FormField(password, { password = it }, "密码", secret = true)
                Button(onClick = { onChildLogin(username, password) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("登录")
                }
            }
        }
    }
}

@Composable
private fun GuardianPanel(
    children: List<FamilyMember>, invite: InviteInfo?, busy: Boolean,
    apiError: String?, resetCompleted: Int,
    onCreateChild: (String, String, String) -> Unit,
    onInvite: (String) -> Unit,
    onRedeem: (String) -> Unit,
    onResetChildPassword: (String, String) -> Unit,
) {
    var childName by rememberSaveable { mutableStateOf("") }
    var childUsername by rememberSaveable { mutableStateOf("") }
    var childPassword by remember { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var resetChild by remember { mutableStateOf<FamilyMember?>(null) }
    var childNewPassword by remember { mutableStateOf("") }
    var resetError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resetCompleted) {
        if (resetCompleted > 0) {
            resetChild = null
            childNewPassword = ""
            resetError = null
        }
    }

    RoundedCard {
        Text("我的孩子", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (children.isEmpty()) Text("还没有关联孩子。", color = Muted)
        children.forEach { child ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(child.displayName, fontWeight = FontWeight.SemiBold)
                Text("账号：${child.username}", color = Muted, fontSize = 13.sp)
                OutlinedButton(onClick = { onInvite(child.id) }, enabled = !busy,
                    shape = RoundedCornerShape(16.dp)) { Text("邀请另一位家长") }
                TextButton(onClick = { resetChild = child; childNewPassword = ""; resetError = null },
                    enabled = !busy) { Text("重置孩子密码") }
            }
        }
        invite?.let {
            Text("邀请码（10 分钟内有效）", color = Muted, fontSize = 13.sp)
            SelectionContainer { Text(it.code, color = Pine, fontWeight = FontWeight.Bold) }
            Text("把邀请码交给另一位家长，由对方在自己的账号中填写。", color = Muted, fontSize = 13.sp)
        }
    }

    RoundedCard {
        Text("创建孩子账号", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        FormField(childName, { childName = it }, "孩子称呼")
        FormField(childUsername, { childUsername = it }, "账号名（4–24 位字母、数字或下划线）")
        FormField(childPassword, { childPassword = it }, "密码（至少 8 位）", secret = true)
        Button(onClick = { onCreateChild(childUsername, childPassword, childName) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("创建账号")
        }
    }

    RoundedCard {
        Text("关联已有孩子", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("请向已经关联这个孩子的家长索取邀请码。", color = Muted, fontSize = 13.sp)
        FormField(inviteCode, { inviteCode = it }, "邀请码")
        Button(onClick = { onRedeem(inviteCode.trim()) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("确认关联")
        }
    }

    resetChild?.let { child ->
        HouseDialog(title = "重置${child.displayName}的密码", marker = "钥",
            confirmLabel = "确认重置", busy = busy,
            onDismiss = { resetChild = null },
            onConfirm = {
                if (childNewPassword.length !in 8..72) {
                    resetError = "新密码需要 8～72 位"
                } else {
                    resetError = null
                    onResetChildPassword(child.id, childNewPassword)
                }
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("重置后，孩子在其他设备上的登录也会失效。", color = Muted, fontSize = 13.sp)
                FormField(childNewPassword, { childNewPassword = it }, "新密码（至少 8 位）", secret = true)
                resetError?.let { Text(it, color = Color(0xFFB33B3B), fontSize = 13.sp) }
                apiError?.let { Text(it, color = Color(0xFFB33B3B), fontSize = 13.sp) }
            }
        }
    }

}

@Composable
private fun ChildPanel(guardians: List<FamilyMember>) {
    RoundedCard {
        Text("我的家长", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        guardians.forEach { guardian -> Text(guardian.displayName) }
        if (guardians.isEmpty()) Text("正在加载家人信息…", color = Muted)
    }
}

@Composable
private fun ServerPanel(url: String, connected: Boolean?, busy: Boolean,
                        onChange: (String) -> Unit, onCheck: () -> Unit) {
    RoundedCard {
        Text("服务器连接", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(when (connected) {
            true -> "已连接"
            false -> "暂时无法连接"
            null -> "尚未检查"
        }, color = if (connected == true) Pine else Muted)
        FormField(url, onChange, "后端地址")
        Text(if (BuildConfig.DEBUG) "本地调试使用电脑的服务地址；也可填写云服务器的 HTTPS 地址。"
            else "默认连接积分小屋云服务器。更换服务器时请填写 HTTPS 地址。",
            color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = onCheck, enabled = !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("检查连接")
        }
    }
}

@Composable
private fun RoundedCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            content()
        }
    }
}

@Composable
private fun FormField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun Feedback(message: String, color: Color) {
    RoundedCard { Text(message, color = color) }
}

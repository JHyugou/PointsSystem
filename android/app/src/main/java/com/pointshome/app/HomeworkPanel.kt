package com.pointshome.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HomeworkPanel(children: List<FamilyMember>, selectedChildId: String?,
                           onSelectChild: (String) -> Unit, token: String, client: ApiClient,
                           onAiSettings: () -> Unit, onPointsChanged: () -> Unit) {
    if (children.isEmpty()) {
        HomeworkCard { Text("请先在家庭管理中创建孩子账号。", color = HouseMuted) }
        return
    }
    val child = children.find { it.id == selectedChildId } ?: children.first()
    if (children.size > 1) HomeworkCard {
        Text("选择孩子", color = HouseInk, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            children.forEach { member ->
                if (member.id == child.id) Button(onClick = { onSelectChild(member.id) },
                    shape = RoundedCornerShape(14.dp)) { Text(member.displayName) }
                else OutlinedButton(onClick = { onSelectChild(member.id) },
                    shape = RoundedCornerShape(14.dp)) { Text(member.displayName) }
            }
        }
    }
    key(child.id) {
        HomeworkChildPanel(child, token, client, onAiSettings, onPointsChanged)
    }
}

@Composable
private fun HomeworkChildPanel(child: FamilyMember, token: String, client: ApiClient,
                               onAiSettings: () -> Unit, onPointsChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<HomeworkSummary>()) }
    var visibleRecords by remember { mutableIntStateOf(10) }
    var detail by remember { mutableStateOf<HomeworkDetail?>(null) }
    var rule by remember { mutableStateOf(HomeworkRule(null, null, null)) }
    var items by remember { mutableStateOf(emptyList<PointItem>()) }
    var aiConfigured by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var showRule by remember { mutableStateOf(false) }
    var draftRule by remember { mutableStateOf(rule) }
    var confirmReview by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf(emptyList<Uri>()) }
    var cameraFiles by remember { mutableStateOf(emptyList<File>()) }
    var cameraPending by remember { mutableStateOf<Pair<Uri, File>?>(null) }
    var newSubject by remember { mutableStateOf("数学") }
    var newInstructions by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var photoPage by remember { mutableIntStateOf(0) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var reviewRating by remember { mutableStateOf<String?>(null) }
    var reviewNote by remember { mutableStateOf("") }
    var reviewItemId by remember { mutableStateOf<String?>(null) }
    var itemOverridden by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) {
            cameraFiles.forEach { it.delete() }
            cameraFiles = emptyList()
            selectedUris = uris
            formError = null
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPending?.let { (uri, file) ->
            if (success) {
                selectedUris = selectedUris + uri
                cameraFiles = cameraFiles + file
                formError = null
            } else file.delete()
        }
        cameraPending = null
    }

    LaunchedEffect(child.id, token) {
        loading = true
        try {
            records = client.homeworkList(token, child.id)
            rule = client.homeworkRule(token, child.id)
            items = client.points(token, child.id).items
            aiConfigured = client.deepSeekSetting(token).configured
        } catch (ex: Exception) {
            error = ex.message ?: "无法读取作业记录"
        } finally { loading = false }
    }

    LaunchedEffect(detail?.id) { photoPage = 0 }
    LaunchedEffect(detail?.id, photoPage) {
        val active = detail ?: return@LaunchedEffect
        photoBitmap = null
        try {
            val bytes = client.homeworkPhoto(token, active.id, photoPage)
            photoBitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        } catch (ex: Exception) {
            error = ex.message ?: "无法显示作业照片"
        }
    }

    LaunchedEffect(detail?.id, detail?.status) {
        val active = detail ?: return@LaunchedEffect
        reviewRating = active.parentRating
        reviewNote = active.parentNote.orEmpty()
        itemOverridden = false
    }
    LaunchedEffect(reviewRating, rule, detail?.id) {
        if (!itemOverridden && detail?.status == "ANALYZED") {
            reviewItemId = suggestedItem(rule, reviewRating)
        }
    }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            notice = null
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (ex: Exception) { error = ex.message ?: "操作失败，请稍后重试" }
            finally { busy = false }
        }
    }

    if (showNew) {
        HouseDialog(title = "拍下作业", marker = "✎", confirmLabel = "上传并分析",
            busy = busy, onDismiss = {
                showNew = false
                cameraFiles.forEach { it.delete() }
                cameraFiles = emptyList()
                selectedUris = emptyList()
            }, onConfirm = {
                if (newSubject.isBlank() || newSubject.length > 40 || newInstructions.length > 500
                    || selectedUris.isEmpty()) {
                    formError = "请填写科目并选择 1 到 3 张清晰的照片"
                } else if (!aiConfigured) {
                    formError = "请先在 AI 设置中保存 DeepSeek 密钥和图片模型"
                } else runAction {
                    val compressed = selectedUris.map { compressHomeworkImage(context, it) }
                    val uploaded = client.uploadHomework(token, child.id,
                        newSubject.trim(), newInstructions.trim(), compressed)
                    cameraFiles.forEach { it.delete() }
                    cameraFiles = emptyList()
                    selectedUris = emptyList()
                    showNew = false
                    detail = uploaded
                    records = client.homeworkList(token, child.id)
                    detail = client.analyzeHomework(token, uploaded.id)
                    records = client.homeworkList(token, child.id)
                    notice = "分析完成，请核对每一条建议"
                }
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HouseTextField(newSubject, { newSubject = it }, "科目，例如数学")
                OutlinedTextField(value = newInstructions, onValueChange = { newInstructions = it },
                    label = { Text("答案或特别要求（可选）") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    shape = RoundedCornerShape(15.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("从相册选图") }
                    OutlinedButton(onClick = {
                        if (selectedUris.size >= 3) formError = "最多选择 3 张照片"
                        else {
                            cameraPending = newHomeworkCameraUri(context)
                            camera.launch(cameraPending!!.first)
                        }
                    }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("拍照") }
                }
                Text("已选 ${selectedUris.size}/3 张", color = HouseMuted)
                HomeworkSelectedPreview(selectedUris)
                HouseHint("作业照片将发送给 DeepSeek。AI 只提供检查建议；评价和积分由家长决定。调用可能产生费用。")
                formError?.let { Text(it, color = HomeworkRed, fontSize = 13.sp) }
            }
        }
    }

    if (showRule) {
        HouseDialog(title = "评价与积分建议", marker = "★", confirmLabel = "保存规则",
            busy = busy, onDismiss = { showRule = false }, onConfirm = { runAction {
                rule = client.saveHomeworkRule(token, child.id, draftRule)
                showRule = false
                notice = "规则已保存；发放积分仍需家长确认"
            } }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HouseHint("家长选择“好 / 一般 / 差”后，推荐对应积分项目；是否发放仍由家长确认。负数项目延迟 30 分钟生效。")
                HomeworkItemPicker("好", draftRule.goodItemId, items) {
                    draftRule = draftRule.copy(goodItemId = it)
                }
                HomeworkItemPicker("一般", draftRule.averageItemId, items) {
                    draftRule = draftRule.copy(averageItemId = it)
                }
                HomeworkItemPicker("差", draftRule.poorItemId, items) {
                    draftRule = draftRule.copy(poorItemId = it)
                }
            }
        }
    }

    if (confirmReview && detail != null) {
        HouseDialog(title = "确认作业评价", marker = "✓", confirmLabel = "确认并处理积分",
            busy = busy, onDismiss = { confirmReview = false }, onConfirm = {
                val rating = reviewRating
                if (rating == null) {
                    error = "请先选择家长评价"
                    confirmReview = false
                } else runAction {
                    val updated = client.reviewHomework(token, detail!!.id, rating,
                        reviewNote, reviewItemId)
                    detail = updated
                    records = client.homeworkList(token, child.id)
                    confirmReview = false
                    onPointsChanged()
                    notice = if (reviewItemId == null) "作业评价已确认" else "作业评价已确认，积分项目已提交"
                }
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("家长评价：${ratingLabel(reviewRating)}", color = HouseInk)
                Text("积分项目：" + (items.find { it.id == reviewItemId }?.let {
                    "${it.name}（${if (it.points > 0) "+" else ""}${it.points} 分）"
                } ?: "不发放积分"), color = HouseInk)
                HouseHint("请先核对照片和 AI 的逐题意见。确认后不能再次发放这份作业的积分；积分操作仍可在积分记录中撤销。")
            }
        }
    }

    if (confirmDelete && detail != null) {
        HouseDialog(title = "删除作业记录", marker = "!", confirmLabel = "确认删除",
            busy = busy, onDismiss = { confirmDelete = false }, onConfirm = { runAction {
                client.deleteHomework(token, detail!!.id)
                detail = null
                records = client.homeworkList(token, child.id)
                confirmDelete = false
                notice = "作业照片和评价已删除"
            } }) {
            Text("删除照片和评价不会撤销已经发放或待生效的积分。", color = HouseMuted)
        }
    }

    HomeworkCard {
        Text("${child.displayName}的作业", color = HouseInk, fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold)
        Text("拍照或选取最多 3 张照片，AI 给出逐题建议，最终由家长确认。",
            color = HouseMuted, fontSize = 13.sp)
        if (!aiConfigured && !loading) {
            HouseHint("DeepSeek 尚未配置。请先填写密钥并选择支持图片的模型。")
            OutlinedButton(onClick = onAiSettings, shape = RoundedCornerShape(14.dp)) {
                Text("前往 AI 设置")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { formError = null; showNew = true }, enabled = !busy && aiConfigured,
                shape = RoundedCornerShape(14.dp)) { Text("＋ 新作业") }
            OutlinedButton(onClick = { draftRule = rule; showRule = true },
                enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("评价规则") }
        }
        if (loading || busy) CircularProgressIndicator(color = HouseGreen)
    }
    error?.let { HomeworkMessage(it, HomeworkRed) }
    notice?.let { HomeworkMessage(it, HouseGreen) }

    if (records.isEmpty() && !loading) HomeworkCard {
        Text("还没有作业记录。", color = HouseMuted)
    }
    if (records.isNotEmpty()) HomeworkCard {
        Text("作业记录", color = HouseInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        records.take(visibleRecords).forEach { record ->
            OutlinedButton(onClick = { runAction {
                detail = client.homeworkDetail(token, record.id)
            } }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("${record.subject} · ${homeworkStatus(record.status)}", color = HouseInk)
                    Text("${homeworkDate(record.createdAt)} · ${record.photoCount} 张 · " +
                        (record.parentRating?.let { "家长评价 ${ratingLabel(it)}" } ?: "尚未评价"),
                        color = HouseMuted, fontSize = 12.sp)
                }
            }
        }
        if (records.size > visibleRecords) TextButton(onClick = { visibleRecords += 10 }) {
            Text("查看更多作业（剩余 ${records.size - visibleRecords} 份）")
        }
    }

    detail?.let { active ->
        HomeworkCard {
            Text("${active.subject} · ${homeworkStatus(active.status)}", color = HouseInk,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(homeworkDate(active.createdAt), color = HouseMuted, fontSize = 12.sp)
            active.instructions?.takeIf { it.isNotBlank() }?.let {
                HouseHint("家长补充：$it")
            }
            photoBitmap?.let { bitmap ->
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "作业第 ${photoPage + 1} 张",
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                        .background(HouseMist, RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit)
            }
            if (active.photoCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { photoPage-- }, enabled = photoPage > 0,
                    shape = RoundedCornerShape(14.dp)) { Text("上一张") }
                Text("${photoPage + 1}/${active.photoCount}", modifier = Modifier.padding(top = 12.dp),
                    color = HouseMuted)
                OutlinedButton(onClick = { photoPage++ },
                    enabled = photoPage < active.photoCount - 1,
                    shape = RoundedCornerShape(14.dp)) { Text("下一张") }
            }
            if (active.status in listOf("SUBMITTED", "FAILED", "ANALYZED")) {
                active.aiError?.let { Text(it, color = HomeworkRed) }
                Button(onClick = { runAction {
                    detail = client.analyzeHomework(token, active.id)
                    records = client.homeworkList(token, child.id)
                    notice = "分析完成，请核对结果"
                } }, enabled = !busy && aiConfigured && active.status != "REVIEWED",
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(if (active.status == "SUBMITTED") "开始 AI 分析" else "重新分析（可能产生费用）")
                }
            }
            if (active.status == "ANALYZING") HouseHint("正在分析，请稍后返回并刷新。")
            OutlinedButton(onClick = { runAction {
                detail = client.homeworkDetail(token, active.id)
                records = client.homeworkList(token, child.id)
            } }, enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("刷新这份作业") }
        }

        active.analysis?.let { analysis ->
            HomeworkCard {
                Text("AI 检查建议", color = HouseInk, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("模型：${active.modelId.orEmpty()}",
                    color = HouseMuted, fontSize = 12.sp)
                Text(analysis.summary, color = HouseInk)
                HomeworkList("做得好的地方", analysis.strengths)
                if (analysis.problems.isNotEmpty()) {
                    Text("需要核对的题目", color = HouseInk, fontWeight = FontWeight.SemiBold)
                    analysis.problems.forEach { problem ->
                        Text("${problem.location.ifBlank { "题目" }}：${problem.issue}", color = HouseInk)
                        if (problem.suggestion.isNotBlank()) Text("建议：${problem.suggestion}",
                            color = HouseMuted, fontSize = 13.sp)
                    }
                }
                HomeworkList("尚不确定", analysis.uncertainties)
                HomeworkList("下一步建议", analysis.nextSteps)
                HouseHint("AI 可能误读字迹或题意，请对照照片核查。")
            }

            HomeworkCard {
                Text("继续追问", color = HouseInk, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold)
                active.chat.forEach { message ->
                    Text((if (message.role == "USER") "家长：" else "助手：") + message.content,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (message.role == "USER") HouseSoftGreen else HouseMist,
                                RoundedCornerShape(14.dp)).padding(12.dp), color = HouseInk)
                }
                Text("已用 ${active.chat.count { it.role == "USER" }}/5 次追问，每次可能产生费用。",
                    color = HouseMuted, fontSize = 12.sp)
                if (active.chat.count { it.role == "USER" } < 5) {
                    OutlinedTextField(value = question, onValueChange = { question = it },
                        label = { Text("例如：第 3 题错在哪里？") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                        shape = RoundedCornerShape(15.dp))
                    OutlinedButton(onClick = { runAction {
                        detail = client.askHomework(token, active.id, question.trim())
                        question = ""
                    } }, enabled = !busy && question.isNotBlank() && question.length <= 500,
                        shape = RoundedCornerShape(14.dp)) { Text("发送追问") }
                }
            }
        }

        if (active.status == "ANALYZED") HomeworkCard {
            Text("家长评价与积分", color = HouseInk, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold)
            HouseHint("请根据作业和 AI 建议选择评价。积分项目只作推荐，可改选或不发放。")
            HomeworkRatingPicker(reviewRating) { reviewRating = it }
            OutlinedTextField(value = reviewNote, onValueChange = { reviewNote = it },
                label = { Text("给孩子的评语（可选）") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                shape = RoundedCornerShape(15.dp))
            HomeworkItemPicker("本次积分项目", reviewItemId, items) {
                reviewItemId = it
                itemOverridden = true
            }
            Button(onClick = { confirmReview = true }, enabled = !busy &&
                reviewRating != null && reviewNote.length <= 500,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("确认评价")
            }
        }
        if (active.status == "REVIEWED") HomeworkCard {
            Text("家长已确认", color = HouseInk, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold)
            Text("家长评价：${ratingLabel(active.parentRating)}", color = HouseGreen,
                fontSize = 22.sp, fontWeight = FontWeight.Bold)
            active.parentNote?.takeIf { it.isNotBlank() }?.let { Text(it, color = HouseInk) }
            Text(if (active.pointActionId == null) "未发放积分项目"
                else "已提交积分项目；负数积分 30 分钟后生效，可在积分记录中查看或撤销。",
                color = HouseMuted, fontSize = 13.sp)
        }
        OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy,
            shape = RoundedCornerShape(14.dp)) { Text("删除这份作业", color = HomeworkRed) }
    }
}

@Composable
private fun HomeworkSelectedPreview(uris: List<Uri>) {
    val context = LocalContext.current
    var previews by remember { mutableStateOf(emptyList<Bitmap>()) }
    LaunchedEffect(uris) {
        previews = uris.mapNotNull { runCatching { homeworkPreview(context, it) }.getOrNull() }
    }
    if (previews.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        previews.forEachIndexed { index, bitmap ->
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "待上传第 ${index + 1} 张",
                modifier = Modifier.size(76.dp).background(HouseMist, RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun HomeworkRatingPicker(selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("家长评价", color = HouseMuted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("GOOD" to "好", "AVERAGE" to "一般", "POOR" to "差")
                .forEach { (value, label) ->
                    if (selected == value) Button(onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(label)
                    } else OutlinedButton(onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(label)
                    }
                }
        }
    }
}

@Composable
private fun HomeworkItemPicker(label: String, selectedId: String?, items: List<PointItem>,
                               onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = HouseMuted, fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp)) {
                val item = items.find { it.id == selectedId }
                Text(item?.let { "${it.name} · ${if (it.points > 0) "+" else ""}${it.points} 分" }
                    ?: "不推荐／不发放", color = HouseInk)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                containerColor = Color.White, shape = RoundedCornerShape(18.dp)) {
                DropdownMenuItem(text = { Text("不推荐／不发放") }, onClick = {
                    onSelect(null); expanded = false
                })
                items.forEach { item ->
                    DropdownMenuItem(text = {
                        Text("${item.name} · ${if (item.points > 0) "+" else ""}${item.points} 分")
                    }, onClick = { onSelect(item.id); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun HomeworkList(title: String, entries: List<String>) {
    if (entries.isNotEmpty()) {
        Text(title, color = HouseInk, fontWeight = FontWeight.SemiBold)
        entries.forEach { Text("• $it", color = HouseInk) }
    }
}

@Composable
private fun HomeworkCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            content()
        }
    }
}

@Composable
private fun HomeworkMessage(message: String, color: Color) {
    HomeworkCard { Text(message, color = color) }
}

private val HomeworkRed = Color(0xFFAE4D48)
private fun suggestedItem(rule: HomeworkRule, rating: String?): String? = when (rating) {
    "GOOD" -> rule.goodItemId
    "AVERAGE" -> rule.averageItemId
    "POOR" -> rule.poorItemId
    else -> null
}
private fun homeworkStatus(status: String): String = when (status) {
    "SUBMITTED" -> "待分析"
    "ANALYZING" -> "分析中"
    "ANALYZED" -> "待家长确认"
    "FAILED" -> "分析失败"
    "REVIEWED" -> "已确认"
    else -> status
}
private fun ratingLabel(rating: String?): String = when (rating) {
    "GOOD" -> "好"
    "AVERAGE" -> "一般"
    "POOR" -> "差"
    else -> "未评价"
}
private fun homeworkDate(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

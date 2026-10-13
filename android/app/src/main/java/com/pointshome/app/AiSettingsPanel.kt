package com.pointshome.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun AiSettingsPanel(client: ApiClient, token: String) {
    val scope = rememberCoroutineScope()
    var setting by remember(token) { mutableStateOf<AiSetting?>(null) }
    var draftKey by remember(token) { mutableStateOf("") }
    var selectedId by remember(token) { mutableStateOf<String?>(null) }
    var models by remember(token) { mutableStateOf(emptyList<AiModel>()) }
    var loading by remember(token) { mutableStateOf(true) }
    var busy by remember(token) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client, token) {
        loading = true
        try {
            val saved = client.deepSeekSetting(token)
            setting = saved
            selectedId = saved.modelId
        } catch (ex: Exception) {
            error = ex.message ?: "无法读取 AI 设置"
        } finally {
            loading = false
        }
    }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            feedback = null
            try { action() }
            catch (ex: Exception) { error = ex.message ?: "操作失败，请稍后重试" }
            finally { busy = false }
        }
    }

    val visionModels = models.filter { it.acceptsImages }
    val selected = visionModels.find { it.id == selectedId }
    val savedModel = setting?.modelId

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("DeepSeek", color = HouseInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("为作业图片批改准备模型。密钥由后端加密保存，只有家长能管理。",
                color = HouseMuted, fontSize = 14.sp)

            if (loading) {
                CircularProgressIndicator(color = HouseGreen)
            } else {
                HouseHint(if (setting?.configured == true)
                    "已保存密钥。留空表示继续使用已有密钥；填写新密钥可替换。"
                else "尚未保存密钥。请填写 DeepSeek 开放平台的 API 密钥。")

                OutlinedTextField(
                    value = draftKey,
                    onValueChange = { draftKey = it; models = emptyList() },
                    label = { Text("API 密钥") },
                    placeholder = { Text(if (setting?.configured == true) "留空以使用已有密钥" else "输入 API 密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HouseGreen,
                        unfocusedBorderColor = HouseLine,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                    ),
                )

                OutlinedButton(
                    onClick = { act {
                        models = client.refreshDeepSeekModels(token, draftKey).models
                        feedback = "已从 DeepSeek 更新 ${models.size} 个模型，其中 ${models.count { it.acceptsImages }} 个支持图片"
                    } },
                    enabled = !busy && (draftKey.isNotBlank() || setting?.configured == true),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, HouseLine),
                ) { Text("联网刷新模型") }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        enabled = !busy && visionModels.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, HouseLine),
                    ) {
                        Text(selected?.let { "${it.name}  ·  ${it.id}" }
                            ?: if (selectedId != null && models.isEmpty()) "已选模型：$selectedId"
                            else if (selectedId != null) "已选模型不在当前图片模型列表中，请重选"
                            else "选择可识别作业图片的模型",
                            color = if (visionModels.isEmpty()) HouseMuted else HouseInk)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(Color.White).heightIn(max = 360.dp),
                        containerColor = Color.White,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        visionModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.name, color = HouseInk)
                                        Text(model.id, color = HouseMuted, fontSize = 12.sp)
                                    }
                                },
                                onClick = { selectedId = model.id; menuOpen = false },
                            )
                        }
                    }
                }

                if (models.isNotEmpty() && visionModels.isEmpty()) {
                    Text("当前在线列表没有可识别图片的模型，请稍后刷新。", color = HouseMuted)
                }
                if (models.isEmpty() && setting?.configured == true) {
                    Text("需要更换模型时，点击“联网刷新模型”。", color = HouseMuted, fontSize = 12.sp)
                }
                if (savedModel != null && selectedId != savedModel) {
                    Text("已保存模型：$savedModel。新选择尚未保存。", color = HouseMuted, fontSize = 12.sp)
                }

                Button(
                    onClick = { act {
                        val modelId = selectedId ?: return@act
                        setting = client.saveDeepSeekSetting(token, draftKey, modelId)
                        draftKey = ""
                        feedback = "已保存 DeepSeek 设置"
                    } },
                    enabled = !busy && selected != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("保存密钥与模型") }

                OutlinedButton(
                    onClick = { act {
                        client.testDeepSeek(token)
                        feedback = "模型调用成功，可以继续接入作业批改"
                    } },
                    enabled = !busy && setting?.configured == true && draftKey.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, HouseLine),
                ) { Text("测试模型调用") }
                Text("测试会向所选模型发送一条短消息，可能产生少量 API 费用。",
                    color = HouseMuted, fontSize = 12.sp)

                if (setting?.configured == true) {
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, HouseLine)) {
                        Text("删除已保存的密钥", color = Color(0xFFAE4D48))
                    }
                }
            }
        }
    }

    error?.let { AiFeedback(it, Color(0xFFAE4D48)) }
    feedback?.let { AiFeedback(it, HouseGreen) }

    if (confirmDelete) {
        HouseDialog(title = "删除 API 密钥", marker = "!", confirmLabel = "确认删除",
            onConfirm = { act {
                client.removeDeepSeek(token)
                setting = AiSetting(false, null)
                draftKey = ""
                selectedId = null
                models = emptyList()
                confirmDelete = false
                feedback = "已删除 DeepSeek 设置"
            } }, onDismiss = { confirmDelete = false }, busy = busy) {
            Text("删除后需要重新输入密钥并选择模型。", color = HouseMuted)
        }
    }
}

@Composable
private fun AiFeedback(message: String, color: Color) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Text(message, modifier = Modifier.padding(16.dp), color = color)
    }
}

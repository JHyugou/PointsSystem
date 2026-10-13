package com.pointshome.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val HouseGreen = Color(0xFF27685E)
internal val HouseInk = Color(0xFF213B38)
internal val HouseMuted = Color(0xFF66807A)
internal val HouseMist = Color(0xFFF4F8F5)
internal val HouseLine = Color(0xFFD8E5E0)
internal val HouseSoftGreen = Color(0xFFE7F2EE)

@Composable
internal fun HouseDialog(
    title: String,
    marker: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    cancelLabel: String = "取消",
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().safeDrawingPadding().imePadding()
            .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            val dialogHeight = minOf(620.dp, maxHeight)
            Surface(
                modifier = Modifier.padding(horizontal = 20.dp)
                    .widthIn(max = 440.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 18.dp,
            ) {
                Column(
                    modifier = Modifier.heightIn(max = dialogHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp)
                                .background(HouseSoftGreen, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(marker, color = HouseGreen, fontSize = 20.sp,
                                fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, color = HouseInk, fontSize = 21.sp,
                                fontWeight = FontWeight.Bold)
                            subtitle?.let { Text(it, color = HouseMuted, fontSize = 12.sp) }
                        }
                    }

                    content()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(15.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HouseLine),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HouseInk),
                        ) { Text(cancelLabel, fontSize = 14.sp) }
                        Button(
                            onClick = onConfirm,
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HouseGreen),
                        ) { Text(confirmLabel, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HouseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = HouseInk,
            unfocusedTextColor = HouseInk,
            focusedLabelColor = HouseGreen,
            unfocusedLabelColor = HouseMuted,
            focusedBorderColor = HouseGreen,
            unfocusedBorderColor = HouseLine,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            cursorColor = HouseGreen,
        ),
    )
}

@Composable
internal fun HouseHint(message: String) {
    Surface(color = HouseMist, shape = RoundedCornerShape(14.dp)) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(12.dp),
            color = HouseMuted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

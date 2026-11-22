package com.chengyan.app.ui.screens

/*
 * 用户资料编辑弹层 —— 从设置页或对话里点自己的头像进入。
 * 头像选择复制进应用私有目录（与灵魂卡头像同管线）；保存全局生效。
 */

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengyan.app.data.UserProfile
import com.chengyan.app.ui.theme.Accent
import com.chengyan.app.ui.theme.AccentSoft
import com.chengyan.app.ui.theme.Ink
import com.chengyan.app.ui.theme.Ink2
import com.chengyan.app.ui.theme.Ink4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UserProfileDialog(
    initial: UserProfile,
    onSave: (UserProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var avatarUri by remember { mutableStateOf(initial.avatarUri) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val path = runCatching {
                    val dir = File(ctx.filesDir, "chengyan_avatars").apply { mkdirs() }
                    val dst = File(dir, "user_${System.currentTimeMillis()}.jpg")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                    }
                    dst.absolutePath
                }.getOrNull()
                if (path != null) avatarUri = path
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用户资料", fontSize = 17.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.size(72.dp).background(AccentSoft, CircleShape).clickable { imagePicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = remember(avatarUri) {
                        if (avatarUri.isBlank()) null
                        else runCatching { android.graphics.BitmapFactory.decodeFile(avatarUri) }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), "用户头像",
                            Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else {
                        Text(name.ifBlank { "你" }.take(1), fontSize = 26.sp, color = Accent)
                    }
                    Text("更换", fontSize = 10.sp, color = Ink2,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(16) },
                    label = { Text("用户名称", fontSize = 13.sp, color = Ink4) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("设置后，你在对话中的头像和名称会全局显示", fontSize = 11.sp, color = Ink4)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(UserProfile(name = name.trim(), avatarUri = avatarUri)); onDismiss() }) {
                Text("保存", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Ink2) }
        },
    )
}

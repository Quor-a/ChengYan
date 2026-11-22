@file:OptIn(ExperimentalMaterial3Api::class)

package com.chengyan.app.ui.screens

/*
 * 灵魂注入 —— UI 语义完整移植自 Quor-a/ZorvAI QuroSoulUi.kt：
 *   · SoulInjectionSheet：底部弹层，灵魂卡列表，点卡=激活并用它开新对话；
 *     每卡 编辑 / 删除；顶部 新建灵魂卡；底部「不使用灵魂」。
 *   · SoulEditDialog：全屏编辑（头像[图片上传] / 名称 / 一句话描述 / 角色设定 / 聊天设定）。
 *   · 头像未上传时退化为「名称首字」圆标（ZorvAI 同语义，表情头像已移除）。
 * 视觉为澄言原创暖纸×墨×陶土橙。
 */

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chengyan.app.data.SoulCard
import com.chengyan.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/* ==================== 灵魂注入主入口（底部弹层 · ZorvAI SoulInjectionSheet 同构） ==================== */

@Composable
fun SoulInjectionSheet(
    souls: List<SoulCard>,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (SoulCard) -> Unit,
    onDelete: (SoulCard) -> Unit,
    onPick: (SoulCard?) -> Unit,   // 点卡=用该灵魂开新对话；null=不使用灵魂
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("灵魂注入", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("选一张灵魂卡开始新对话；卡片可在编辑中完善", fontSize = 11.sp, color = Ink3, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCreate) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp), tint = AccentInk)
                    Spacer(Modifier.width(4.dp))
                    Text("新建灵魂卡", fontSize = 13.sp, color = AccentInk)
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                items(souls, key = { it.id }) { s ->
                    SoulSmallCard(
                        soul = s,
                        onClick = { onPick(s) },
                        onEdit = { onEdit(s) },
                        onDelete = { onDelete(s) },
                    )
                }
            }
            if (souls.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Text("还没有灵魂卡\n点右上「新建灵魂卡」创造第一张", fontSize = 14.sp, color = Ink3,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 22.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "不使用灵魂（无系统提示词）",
                fontSize = 13.sp, color = Ink3, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { onPick(null) }.padding(vertical = 14.dp),
            )
        }
    }
}

/** 灵魂卡小卡片（ZorvAI PersonaSmallCard 同构：点头像/标题=激活，右侧编辑/删除）。 */
@Composable
private fun SoulSmallCard(
    soul: SoulCard,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SoulAvatar(soul, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(soul.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    soul.description.ifBlank { soul.roleSetting.take(40).ifBlank { "（未填写描述）" } },
                    fontSize = 12.sp, color = Ink3, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Edit, "编辑", Modifier.size(16.dp), tint = Ink3)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.DeleteOutline, "删除", Modifier.size(16.dp), tint = Ink3)
            }
        }
    }
}

/** 灵魂卡头像：图片 或 名称首字圆标（ZorvAI AvatarContent 语义）。 */
@Composable
fun SoulAvatar(soul: SoulCard, size: Int) {
    val bmp = remember(soul.avatarUri) {
        if (soul.avatarUri == "builtin") null
        else runCatching {
            android.graphics.BitmapFactory.decodeFile(soul.avatarUri)
        }.getOrNull()
    }
    if (soul.avatarUri == "builtin") {
        // 内置角色「小澄」：原创 vector 小猫头像
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.chengyan.app.R.drawable.ic_soul_default),
            contentDescription = "小澄",
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "头像",
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier.size(size.dp).clip(CircleShape)
                .background(AccentSoft)
                .border(1.dp, Color(0xFFEBD2B8), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(soul.name.take(1).ifBlank { "灵" }, fontSize = (size * 0.42).sp, color = AccentInk, fontFamily = FontFamily.Serif)
        }
    }
}

/* ==================== 全屏编辑（ZorvAI PersonaEditDialog 同构） ==================== */

@Composable
fun SoulEditDialog(
    initial: SoulCard?,
    onSave: (SoulCard) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val isNew = initial == null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var avatarUri by remember { mutableStateOf(initial?.avatarUri ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var roleSetting by remember { mutableStateOf(initial?.roleSetting ?: "") }
    var chatSetting by remember { mutableStateOf(initial?.chatSetting ?: "") }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // 头像选择 → 复制进应用私有目录（ZorvAI copyAvatarToInternal 语义）
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val path = runCatching {
                    val dir = File(ctx.filesDir, "chengyan_avatars").apply { mkdirs() }
                    val dst = File(dir, "soul_${System.currentTimeMillis()}.jpg")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                    }
                    dst.absolutePath
                }.getOrNull()
                if (path != null) avatarUri = path
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (isNew) "新建灵魂卡" else "编辑灵魂卡", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                            Text("角色设定与聊天设定将作为系统提示词注入", fontSize = 10.sp, color = Ink4)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Ink2) }
                    },
                    actions = {
                        if (!isNew && onDelete != null) {
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Rounded.DeleteOutline, "删除", tint = Err)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // ── 头像 ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SoulAvatar(SoulCard(id = "x", name = name.ifBlank { "灵" }, avatarUri = avatarUri), 64)
                        Spacer(Modifier.width(16.dp))
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Line2),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink2),
                        ) {
                            Icon(if (avatarUri.isBlank()) Icons.Rounded.Add else Icons.Rounded.Edit, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (avatarUri.isBlank()) "上传头像" else "更换头像", fontSize = 13.sp)
                        }
                    }
                    DividerLine()

                    SoulField("名称", name, { name = it }, placeholder = "例如：鲁迅", singleLine = true)
                    SoulField("一句话描述", description, { description = it }, placeholder = "卡片上显示的简介", singleLine = true)

                    DividerLine()
                    SoulField("角色设定", roleSetting, { roleSetting = it },
                        placeholder = "你将扮演……。说话风格、知识领域、立场与禁忌……", minLines = 5)
                    SoulField("聊天设定", chatSetting, { chatSetting = it },
                        placeholder = "语气、格式、长度等附加要求（可选）", minLines = 3)

                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent)
                            .clickable {
                                val now = System.currentTimeMillis()
                                onSave(SoulCard(
                                    id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = name.trim().ifBlank { "未命名灵魂" },
                                    avatarUri = avatarUri,
                                    description = description.trim(),
                                    roleSetting = roleSetting.trim(),
                                    chatSetting = chatSetting.trim(),
                                    createdAt = initial?.createdAt ?: now,
                                    updatedAt = now,
                                ))
                                onDismiss()
                            }
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("保存灵魂卡", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** 下划线字段（与设置页同款编辑排版）。 */
@Composable
private fun SoulField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = Ink4, modifier = Modifier.padding(bottom = 4.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Ink, lineHeight = 22.sp),
            singleLine = singleLine,
            minLines = minLines,
            decorationBox = { inner ->
                Column {
                    if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Ink4, lineHeight = 22.sp)
                    inner()
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                }
            },
        )
    }
}

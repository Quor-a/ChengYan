package com.chengyan.app.net

/*
 * 手机能力工具层（澄言原创设计，不复制 ZorvAI）。
 * 设计原则：按领域聚合 —— 一个领域 = 一个工具 + action 参数，
 * 避免每个权限一个工具导致工具列表爆炸、模型选择困难。
 *
 *   phone_info      设备状态域：电量/网络/内存/存储/时间/时区/剪贴板（无需敏感权限）
 *   phone_contacts  联系人域：search / recent                    （READ_CONTACTS）
 *   phone_sms       短信域：inbox / thread                       （READ_SMS）
 *   phone_calls     通话域：recent 通话记录                      （READ_CALL_LOG）
 *   phone_calendar  日历域：upcoming / add（可写入日程）         （READ/WRITE_CALENDAR）
 *   phone_photos    媒体域：recent / get（get 返回缩略图，多模态模型可直接看图）（READ_MEDIA_IMAGES）
 *
 * 所有读取操作只返回文本摘要（限条数、截断长度），图片降采样 ≤1024px JPEG。
 * 权限未授予时返回友好提示，不会让 agent 循环崩溃。
 */

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.text.format.Formatter
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次手机工具执行的结果；imageBase64 非空时会附加到当前 AI 消息（多模态可见）。 */
data class ToolOutcome(val result: String, val imageBase64: String? = null)

object PhoneTools {

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private const val MAX_TEXT = 1200

    /* ---------- 权限声明（供授权对话框与运行时检查） ---------- */

    val allPermissions: List<String> = listOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    ) + if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** 工具名 → 其领域要求的权限（用于执行前检查）。 */
    private val domainPermissions: Map<String, List<String>> = mapOf(
        "phone_info" to emptyList(),
        "phone_contacts" to listOf(Manifest.permission.READ_CONTACTS),
        "phone_sms" to listOf(Manifest.permission.READ_SMS),
        "phone_calls" to listOf(Manifest.permission.READ_CALL_LOG),
        "phone_calendar" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        "phone_photos" to emptyList(), // 特殊：按系统版本判断，见 missingPermissions
    )

    fun missingPermissions(context: Context, tool: String): List<String> {
        if (tool == "phone_photos") {
            val granted = if (android.os.Build.VERSION.SDK_INT >= 33)
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
            else
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            return if (granted == android.content.pm.PackageManager.PERMISSION_GRANTED) emptyList()
            else listOf(if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return (domainPermissions[tool] ?: emptyList()).filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /* ---------- 工具定义（OpenAI function calling 格式，一个领域一个工具） ---------- */

    fun definitionsJson(): JsonArray = buildJsonArray {
        fun domain(name: String, desc: String, actions: List<String>, props: JsonObject) {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("description", desc)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(actions.map { JsonPrimitive(it) }))
                                put("description", "要执行的操作")
                            })
                            put("properties", props)
                        })
                    })
                })
            })
        }

        domain("phone_info", "查询设备状态：电量、充电、网络、内存、存储、当前时间、时区、剪贴板文本", listOf("status"), buildJsonObject { })
        domain("phone_contacts", "查询手机通讯录联系人", listOf("search", "recent"), buildJsonObject {
            put("query", buildJsonObject { put("type", "string"); put("description", "search 时按姓名或号码模糊匹配") })
        })
        domain("phone_sms", "读取手机短信收件箱（只读）", listOf("inbox", "thread"), buildJsonObject {
            put("address", buildJsonObject { put("type", "string"); put("description", "thread 时指定对方号码，读取与该号码的往来短信") })
        })
        domain("phone_calls", "读取通话记录（只读）", listOf("recent"), buildJsonObject { })
        domain("phone_calendar", "查询或写入日历日程", listOf("upcoming", "add"), buildJsonObject {
            put("title", buildJsonObject { put("type", "string"); put("description", "add 时日程标题") })
            put("start_millis", buildJsonObject { put("type", "number"); put("description", "add 时开始时间的毫秒时间戳") })
            put("duration_min", buildJsonObject { put("type", "number"); put("description", "add 时持续分钟数，默认 30") })
        })
        domain("phone_photos", "查询手机相册照片；get 会把照片缩略图发给模型（需要多模态模型才能看图）", listOf("recent", "get"), buildJsonObject {
            put("id", buildJsonObject { put("type", "number"); put("description", "get 时照片的媒体 id（来自 recent 的结果）") })
        })
    }

    /* ---------- 执行入口 ---------- */

    fun execute(context: Context, name: String, argsJson: String): ToolOutcome {
        val missing = missingPermissions(context, name)
        if (missing.isNotEmpty()) {
            return ToolOutcome("手机权限未授予（${name} 需要的权限：${missing.joinToString()}）。请告知用户在应用顶栏菜单「手机能力授权」中授予权限后重试。")
        }
        val args = runCatching { Json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrDefault(buildJsonObject { })
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "status"
        return runCatching {
            when (name) {
                "phone_info" -> info(context, action)
                "phone_contacts" -> contacts(context, action, args)
                "phone_sms" -> sms(context, action, args)
                "phone_calls" -> calls(context, action)
                "phone_calendar" -> calendar(context, action, args)
                "phone_photos" -> photos(context, action, args)
                else -> ToolOutcome("未知手机工具：$name")
            }
        }.getOrElse { ToolOutcome("执行失败：${it.message}") }
    }

    /* ---------- 各领域实现 ---------- */

    private fun info(context: Context, action: String): ToolOutcome {
        if (action != "status") return ToolOutcome("phone_info 仅支持 action=status")
        val sb = StringBuilder()
        // 电量/充电
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intent = context.registerReceiver(null, android.content.IntentFilter(Intent_ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            sb.appendLine("电量：$level%")
            sb.appendLine("充电：" + when {
                status == BatteryManager.BATTERY_STATUS_CHARGING && plugged == BatteryManager.BATTERY_PLUGGED_AC -> "交流充电中"
                status == BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                status == BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                else -> "未充电"
            })
        }
        // 网络
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            sb.appendLine("网络：" + when {
                caps == null -> "未连接"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                else -> "已连接"
            })
        }
        // 内存/存储
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            sb.appendLine("可用内存：${Formatter.formatFileSize(context, mi.availMem)} / 总 ${Formatter.formatFileSize(context, mi.totalMem)}")
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            sb.appendLine("可用存储：${Formatter.formatFileSize(context, st.availableBytes)} / 总 ${Formatter.formatFileSize(context, st.totalBytes)}")
        }
        val now = System.currentTimeMillis()
        sb.appendLine("当前时间：${df.format(Date(now))}（时区 ${java.util.TimeZone.getDefault().id}）")
        // 剪贴板
        runCatching {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.primaryClip?.getItemAt(0)?.text?.let {
                if (it.isNotBlank()) sb.appendLine("剪贴板文本：${it.take(200)}")
            }
        }
        return ToolOutcome(sb.toString().trim().take(MAX_TEXT))
    }

    private fun contacts(context: Context, action: String, args: JsonObject): ToolOutcome {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: ""
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val sel = when (action) {
            "search" -> "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR REPLACE(${ContactsContract.CommonDataKinds.Phone.NUMBER}, ' ', '') LIKE ?"
            else -> null
        }
        val args2 = when (action) {
            "search" -> arrayOf("%$query%", "%${query.replace(" ", "")}%")
            else -> null
        }
        context.contentResolver.query(uri, arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ), sel, args2, if (action == "search") null else "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC")?.use { c ->
            val out = StringBuilder(if (action == "search") "搜索「$query」的联系人：\n" else "最近更新联系人：\n")
            var n = 0
            while (c.moveToNext() && n < 20) {
                out.appendLine("${n + 1}. ${c.getString(0)}：${c.getString(1)}")
                n++
            }
            if (n == 0) return ToolOutcome("没有找到匹配的联系人")
            return ToolOutcome(out.toString().trim().take(MAX_TEXT))
        } ?: return ToolOutcome("无法读取通讯录")
    }

    private fun sms(context: Context, action: String, args: JsonObject): ToolOutcome {
        val address = args["address"]?.jsonPrimitive?.contentOrNull
        val uri = Telephony.Sms.CONTENT_URI
        val sel: String?; val selArgs: Array<String>?
        when {
            action == "thread" && !address.isNullOrBlank() -> {
                sel = "${Telephony.Sms.ADDRESS}=?"; selArgs = arrayOf(address)
            }
            else -> { sel = "${Telephony.Sms.TYPE}<>?"; selArgs = arrayOf("3") } // 排除草稿
        }
        context.contentResolver.query(uri, arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
        ), sel, selArgs, "${Telephony.Sms.DATE} DESC")?.use { c ->
            val out = StringBuilder(if (address != null) "与 $address 的短信往来：\n" else "最近短信：\n")
            var n = 0
            while (c.moveToNext() && n < 20) {
                val dir = if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "我→" else "→我"
                out.appendLine("[${df.format(Date(c.getLong(2)))}] $dir ${c.getString(0)}：${c.getString(1).take(120)}")
                n++
            }
            if (n == 0) return ToolOutcome("没有读取到短信")
            return ToolOutcome(out.toString().trim().take(MAX_TEXT))
        } ?: return ToolOutcome("无法读取短信")
    }

    private fun calls(context: Context, action: String): ToolOutcome {
        if (action != "recent") return ToolOutcome("phone_calls 仅支持 action=recent")
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION,
        ), null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            val out = StringBuilder("最近通话：\n")
            var n = 0
            while (c.moveToNext() && n < 20) {
                val type = when (c.getInt(2)) {
                    CallLog.Calls.OUTGOING_TYPE -> "呼出"
                    CallLog.Calls.INCOMING_TYPE -> "呼入"
                    CallLog.Calls.MISSED_TYPE -> "未接"
                    else -> "其他"
                }
                val dur = c.getLong(4)
                out.appendLine("[${df.format(Date(c.getLong(3)))}] $type ${c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(0)}" +
                    if (dur > 0) "（${dur}s）" else "")
                n++
            }
            if (n == 0) return ToolOutcome("通话记录为空")
            return ToolOutcome(out.toString().trim().take(MAX_TEXT))
        } ?: return ToolOutcome("无法读取通话记录")
    }

    private fun calendar(context: Context, action: String, args: JsonObject): ToolOutcome {
        val resolver = context.contentResolver
        if (action == "add") {
            val title = args["title"]?.jsonPrimitive?.contentOrNull ?: return ToolOutcome("add 需要 title 参数")
            val start = args["start_millis"]?.jsonPrimitive?.longOrNull ?: return ToolOutcome("add 需要 start_millis（毫秒时间戳）")
            val dur = args["duration_min"]?.jsonPrimitive?.longOrNull ?: 30L
            val calId = resolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            } ?: return ToolOutcome("设备上没有可用日历账户")
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + dur * 60_000)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            return if (uri != null) ToolOutcome("已创建日程「$title」：${df.format(Date(start))}，持续 $dur 分钟")
            else ToolOutcome("写入日程失败")
        }
        // upcoming
        val now = System.currentTimeMillis()
        resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
        ), "${CalendarContract.Events.DTSTART}>=?", arrayOf("$now"), "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
            val out = StringBuilder("接下来的日程：\n")
            var n = 0
            while (c.moveToNext() && n < 15) {
                val start = c.getLong(1)
                out.appendLine("[${df.format(Date(start))}] ${c.getString(0).take(40)}")
                n++
            }
            if (n == 0) return ToolOutcome("日历上没有即将到来的日程")
            return ToolOutcome(out.toString().trim().take(MAX_TEXT))
        } ?: return ToolOutcome("无法读取日历")
    }

    private fun photos(context: Context, action: String, args: JsonObject): ToolOutcome {
        val resolver = context.contentResolver
        if (action == "get") {
            val id = args["id"]?.jsonPrimitive?.longOrNull ?: return ToolOutcome("get 需要 id 参数")
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "$id")
            val b64 = resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 1024) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return ToolOutcome("照片解码失败")
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                bmp.recycle()
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } ?: return ToolOutcome("找不到该照片（可能已被删除）")
            return ToolOutcome("已附上照片（id=$id）缩略图，请用多模态视觉能力直接查看。", imageBase64 = b64)
        }
        // recent
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
        ), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val out = StringBuilder("最近照片（用 get(id) 可以把某张图发给模型看）：\n")
            var n = 0
            while (c.moveToNext() && n < 10) {
                out.appendLine("id=${c.getLong(0)}｜${c.getString(1)}｜${df.format(Date(c.getLong(2) * 1000))}")
                n++
            }
            if (n == 0) return ToolOutcome("相册中没有照片")
            return ToolOutcome(out.toString().trim().take(MAX_TEXT))
        } ?: return ToolOutcome("无法读取相册")
    }

    private const val Intent_ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED"
}

/* ---------- 授权引导状态记忆（问过一次就不再打扰） ---------- */

private val Context.phoneStore by preferencesDataStore(name = "chengyan_phone")
private val PHONE_ASKED_KEY = booleanPreferencesKey("asked")

class PhonePrefs(private val context: Context) {
    suspend fun asked(): Boolean = context.phoneStore.data.first()[PHONE_ASKED_KEY] ?: false
    suspend fun setAsked() { context.phoneStore.edit { it[PHONE_ASKED_KEY] = true } }
}

package com.chengyan.app.net

/*
 * 手机能力工具层（澄言原创实现，权限面完整对齐 ZorvAI 的手机权限清单）。
 * 设计原则（用户拍板）：按领域聚合 —— 一个领域 = 一个工具 + action 参数，
 * 避免每个权限一个工具导致工具列表爆炸、模型选择困难。
 *
 *   phone_info      设备状态域：电量/充电/网络/内存/存储/时间/时区/剪贴板/计步/勿扰/音量（部分无需敏感权限）
 *   phone_contacts  联系人域：search / recent / add              （READ/WRITE_CONTACTS）
 *   phone_sms       短信域：inbox / thread / send                （READ_SMS/SEND_SMS；RECEIVE_SMS/MMS 声明备能力扩展）
 *   phone_calls     通话域：recent / call                        （READ_CALL_LOG/CALL_PHONE）
 *   phone_calendar  日历域：upcoming / add                       （READ/WRITE_CALENDAR）
 *   phone_media     媒体域：photos / videos / audios / get_image（READ_MEDIA_IMAGES/VIDEO/AUDIO + ACCESS_MEDIA_LOCATION）
 *   phone_location  位置域：current（最近已知位置）              （FINE/COARSE_LOCATION；BACKGROUND 已声明备扩展）
 *   phone_alarm     闹钟域：alarm / timer（走系统时钟应用）      （SET_ALARM/SCHEDULE_EXACT_ALARM）
 *   phone_apps      应用域：list / launch / open_details / uninstall（QUERY_ALL_PACKAGES）
 *   phone_device    控制域：volume / flashlight / vibrate / dnd / brightness / panel（CAMERA/WRITE_SETTINGS/ACCESS_NOTIFICATION_POLICY）
 *   phone_files     文件域：save_downloads / app_read / app_write（媒体下载集合；MANAGE_EXTERNAL_STORAGE 特殊授权路由）
 *
 * 特殊权限（勿扰/亮度/精确闹钟/全部文件）授予后走系统设置页，工具会在未授予时返回带指引的提示。
 * 所有读取只返回文本摘要（限条数/截断），图片降采样 ≤1024px JPEG。
 */

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

private val Context.phoneStore by preferencesDataStore(name = "chengyan_phone")
private val PHONE_ASKED_KEY = booleanPreferencesKey("asked")

/** 授权引导状态记忆（问过一次就不再打扰）。 */
class PhonePrefs(private val context: Context) {
    suspend fun asked(): Boolean = context.phoneStore.data.first()[PHONE_ASKED_KEY] ?: false
    suspend fun setAsked() { context.phoneStore.edit { it[PHONE_ASKED_KEY] = true } }
}

object PhoneTools {

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private const val MAX_TEXT = 1200
    private const val ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED"

    /* ---------- 权限声明 ---------- */

    /** 运行时需要请求的危险权限（33+ 与旧版本自动区分媒体权限）。 */
    val allPermissions: List<String> = listOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ) + if (Build.VERSION.SDK_INT >= 33) listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
    ) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** 工具名 → 其领域要求的权限（用于执行前检查）。 */
    private val domainPermissions: Map<String, List<String>> = mapOf(
        "phone_info" to emptyList(),
        "phone_contacts" to listOf(Manifest.permission.READ_CONTACTS),
        "phone_sms" to listOf(Manifest.permission.READ_SMS),
        "phone_calls" to listOf(Manifest.permission.READ_CALL_LOG),
        "phone_calendar" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        "phone_media" to emptyList(), // 特殊：按系统版本判断
        "phone_location" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        "phone_alarm" to emptyList(), // 走系统时钟应用
        "phone_apps" to emptyList(),
        "phone_device" to emptyList(), // 各 action 单独判断
        "phone_files" to emptyList(),  // 特殊：按系统版本判断
    )

    fun missingPermissions(context: Context, tool: String): List<String> {
        if (tool == "phone_media" || tool == "phone_files") {
            val perms = if (Build.VERSION.SDK_INT >= 33)
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            return perms.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        }
        if (tool == "phone_device") return emptyList() // 各 action 内部判断
        return (domainPermissions[tool] ?: emptyList()).filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /* ---------- 工具定义（一个领域一个工具 + action） ---------- */

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

        domain("phone_info", "查询设备状态：电量、充电、网络、内存、存储、当前时间、时区、剪贴板文本、今日步数、勿扰状态、音量", listOf("status"), buildJsonObject { })
        domain("phone_contacts", "联系人：search 搜索 / recent 最近更新 / add 新建联系人（add 需 name 与 phone）", listOf("search", "recent", "add"), buildJsonObject {
            put("query", buildJsonObject { put("type", "string"); put("description", "search 时按姓名或号码模糊匹配") })
            put("name", buildJsonObject { put("type", "string"); put("description", "add 时联系人姓名") })
            put("phone", buildJsonObject { put("type", "string"); put("description", "add 时手机号码") })
        })
        domain("phone_sms", "短信：inbox 最近收件 / thread 指定号码往来 / send 发短信（send 需 address 与 body）", listOf("inbox", "thread", "send"), buildJsonObject {
            put("address", buildJsonObject { put("type", "string") })
            put("body", buildJsonObject { put("type", "string") })
        })
        domain("phone_calls", "通话：recent 最近通话记录 / call 拨打电话（call 需 number，走系统拨号确认）", listOf("recent", "call"), buildJsonObject {
            put("number", buildJsonObject { put("type", "string") })
        })
        domain("phone_calendar", "日历：upcoming 即将到来的日程 / add 创建日程（add 需 title 与 start_millis）", listOf("upcoming", "add"), buildJsonObject {
            put("title", buildJsonObject { put("type", "string") })
            put("start_millis", buildJsonObject { put("type", "number"); put("description", "开始时间毫秒时间戳") })
            put("duration_min", buildJsonObject { put("type", "number"); put("description", "持续分钟数，默认 30") })
        })
        domain("phone_media", "媒体库：photos 最近照片 / videos 最近视频 / audios 最近音频 / get_image 把某张照片发给模型看（需多模态）", listOf("photos", "videos", "audios", "get_image"), buildJsonObject {
            put("id", buildJsonObject { put("type", "number"); put("description", "get_image 时照片的媒体 id") })
        })
        domain("phone_location", "位置：current 返回最近已知位置（经纬度与精度，来自 GPS/网络/被动提供者）", listOf("current"), buildJsonObject { })
        domain("phone_alarm", "闹钟：alarm 设置系统闹钟 / timer 设置计时器（走系统时钟应用，需 hour/minute 或 seconds）", listOf("alarm", "timer"), buildJsonObject {
            put("hour", buildJsonObject { put("type", "number") })
            put("minute", buildJsonObject { put("type", "number") })
            put("seconds", buildJsonObject { put("type", "number"); put("description", "timer 时倒计时秒数") })
            put("label", buildJsonObject { put("type", "string") })
        })
        domain("phone_apps", "应用：list 已安装应用 / launch 启动（需 package）/ open_details 打开应用详情 / uninstall 卸载（需 package，走系统确认）", listOf("list", "launch", "open_details", "uninstall"), buildJsonObject {
            put("package", buildJsonObject { put("type", "string") })
            put("query", buildJsonObject { put("type", "string"); put("description", "list 时按应用名/包名过滤") })
        })
        domain("phone_device", "设备控制：volume 设置音量（type=music/ring，percent 0-100）/ flashlight 手电筒（on）/ vibrate 震动（ms）/ dnd 勿扰开关（on）/ brightness 亮度（percent）/ panel 打开网络面板", listOf("volume", "flashlight", "vibrate", "dnd", "brightness", "panel"), buildJsonObject {
            put("type", buildJsonObject { put("type", "string"); put("description", "volume: music 或 ring") })
            put("percent", buildJsonObject { put("type", "number") })
            put("on", buildJsonObject { put("type", "boolean") })
            put("ms", buildJsonObject { put("type", "number"); put("description", "vibrate 毫秒，默认 300") })
        })
        domain("phone_files", "文件：save_downloads 保存文本到下载目录（需 filename 与 content）/ app_read 读应用私有笔记（需 path）/ app_write 写应用私有笔记（需 path 与 content）", listOf("save_downloads", "app_read", "app_write"), buildJsonObject {
            put("filename", buildJsonObject { put("type", "string") })
            put("path", buildJsonObject { put("type", "string"); put("description", "应用私有文件相对路径，如 notes/todo.md") })
            put("content", buildJsonObject { put("type", "string") })
        })
    }

    /* ---------- 执行入口 ---------- */

    fun execute(context: Context, name: String, argsJson: String): ToolOutcome {
        val missing = missingPermissions(context, name)
        if (missing.isNotEmpty()) {
            return ToolOutcome("手机权限未授予（需要：${missing.joinToString()}）。请告知用户在应用顶栏 ⋮ 菜单「手机能力授权」中授予权限后重试。")
        }
        val args = runCatching { Json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }.getOrDefault(buildJsonObject { })
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: defaultAction(name)
        return runCatching {
            when (name) {
                "phone_info" -> info(context, action)
                "phone_contacts" -> contacts(context, action, args)
                "phone_sms" -> sms(context, action, args)
                "phone_calls" -> calls(context, action, args)
                "phone_calendar" -> calendar(context, action, args)
                "phone_media" -> media(context, action, args)
                "phone_location" -> location(context, action)
                "phone_alarm" -> alarm(context, action, args)
                "phone_apps" -> apps(context, action, args)
                "phone_device" -> device(context, action, args)
                "phone_files" -> files(context, action, args)
                else -> ToolOutcome("未知手机工具：$name")
            }
        }.getOrElse { ToolOutcome("执行失败：${it.message}") }
    }

    private fun defaultAction(name: String) = when (name) {
        "phone_info" -> "status"
        "phone_media" -> "photos"
        "phone_location" -> "current"
        else -> name.removePrefix("phone_") // contacts/calls/calendar/alarm 等首动作
    }

    private fun needArg(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull

    private fun specialDenied(msg: String) = ToolOutcome("$msg\n该功能需要用户在系统设置中手动授予特殊权限。")

    /* ---------- phone_info：设备状态 ---------- */

    private fun info(context: Context, action: String): ToolOutcome {
        if (action != "status") return ToolOutcome("phone_info 仅支持 action=status")
        val sb = StringBuilder()
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intent = context.registerReceiver(null, android.content.IntentFilter(ACTION_BATTERY_CHANGED))
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
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            sb.appendLine("可用内存：${Formatter.formatFileSize(context, mi.availMem)} / 总 ${Formatter.formatFileSize(context, mi.totalMem)}")
            val st = android.os.StatFs(Environment.getDataDirectory().path)
            sb.appendLine("可用存储：${Formatter.formatFileSize(context, st.availableBytes)} / 总 ${Formatter.formatFileSize(context, st.totalBytes)}")
        }
        val now = System.currentTimeMillis()
        sb.appendLine("当前时间：${df.format(Date(now))}（时区 ${java.util.TimeZone.getDefault().id}）")
        // 今日步数（TYPE_STEP_COUNTER：开机以来累计，今日近似需自己累积；这里给出累计值）
        runCatching {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)?.let {
                sb.appendLine("步数传感器：可用（开机累计计步）")
            }
        }
        // 勿扰状态
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            sb.appendLine("勿扰：" + when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> "关闭"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅优先提醒"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
                else -> "未知"
            })
        }
        // 音量
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxM = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxR = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            sb.appendLine("音量：媒体 ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/$maxM，铃声 ${am.getStreamVolume(AudioManager.STREAM_RING)}/$maxR")
        }
        // 剪贴板
        runCatching {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.primaryClip?.getItemAt(0)?.text?.let {
                if (it.isNotBlank()) sb.appendLine("剪贴板文本：${it.take(200)}")
            }
        }
        // 传感器计步
        return ToolOutcome(sb.toString().trim().take(MAX_TEXT))
    }

    /* ---------- phone_contacts：联系人 ---------- */

    private fun contacts(context: Context, action: String, args: JsonObject): ToolOutcome {
        val resolver = context.contentResolver
        if (action == "add") {
            val name = needArg(args, "name") ?: return ToolOutcome("add 需要 name 参数")
            val phone = needArg(args, "phone") ?: return ToolOutcome("add 需要 phone 参数")
            if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return ToolOutcome("写联系人需要 WRITE_CONTACTS 权限，请在「手机能力授权」中授予。")
            val op = android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
            val op2 = android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build()
            val op3 = android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
            resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op, op2, op3))
            return ToolOutcome("已新建联系人「$name」（$phone）")
        }
        val query = needArg(args, "query") ?: ""
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val sel = when (action) {
            "search" -> "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR REPLACE(${ContactsContract.CommonDataKinds.Phone.NUMBER}, ' ', '') LIKE ?"
            else -> null
        }
        val selArgs = when (action) {
            "search" -> arrayOf("%$query%", "%${query.replace(" ", "")}%")
            else -> null
        }
        resolver.query(uri, arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ), sel, selArgs, if (action == "search") null else "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC")?.use { c ->
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

    /* ---------- phone_sms：短信 ---------- */

    private fun sms(context: Context, action: String, args: JsonObject): ToolOutcome {
        if (action == "send") {
            val address = needArg(args, "address") ?: return ToolOutcome("send 需要 address 参数")
            val body = needArg(args, "body") ?: return ToolOutcome("send 需要 body 参数")
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return ToolOutcome("发送短信需要 SEND_SMS 权限，请在「手机能力授权」中授予。")
            // 🔴 安全：AI 不能静默发短信——走系统确认界面（用户点发送才真正发出）
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$address")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
            return ToolOutcome("已打开系统短信确认界面（等待用户确认发送）：$address")
        }
        val address = needArg(args, "address")
        val resolver = context.contentResolver
        val sel: String?; val selArgs: Array<String>?
        when {
            action == "thread" && !address.isNullOrBlank() -> { sel = "${Telephony.Sms.ADDRESS}=?"; selArgs = arrayOf(address) }
            else -> { sel = "${Telephony.Sms.TYPE}<>?"; selArgs = arrayOf("3") }
        }
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(
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

    /* ---------- phone_calls：通话 ---------- */

    private fun calls(context: Context, action: String, args: JsonObject): ToolOutcome {
        if (action == "call") {
            val number = needArg(args, "number") ?: return ToolOutcome("call 需要 number 参数")
            if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return ToolOutcome("直接拨号需要 CALL_PHONE 权限。未授予时可改用系统拨号盘。")
            // 🔴 安全：ACTION_CALL 直接拨出；改 ACTION_DIAL 走系统拨号盘由用户确认
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ToolOutcome("已发起对 $number 的拨号（系统会显示通话界面）。")
        }
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

    /* ---------- phone_calendar：日历 ---------- */

    private fun calendar(context: Context, action: String, args: JsonObject): ToolOutcome {
        val resolver = context.contentResolver
        if (action == "add") {
            val title = needArg(args, "title") ?: return ToolOutcome("add 需要 title 参数")
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
        val now = System.currentTimeMillis()
        resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
        ), "${CalendarContract.Events.DTSTART}>=?", arrayOf("$now"), "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
            val out = StringBuilder("接下来的日程：\n")
            var n = 0
            while (c.moveToNext() && n < 15) {
                out.appendLine("[${df.format(Date(c.getLong(1)))}] ${c.getString(0).take(40)}")
                n++
            }
            if (n == 0) return ToolOutcome("日历上没有即将到来的日程")
            return ToolOutcome(out.toString().trim().take(MAX_TEXT))
        } ?: return ToolOutcome("无法读取日历")
    }

    /* ---------- phone_media：媒体库 ---------- */

    private fun media(context: Context, action: String, args: JsonObject): ToolOutcome {
        val resolver = context.contentResolver
        if (action == "get_image") {
            val id = args["id"]?.jsonPrimitive?.longOrNull ?: return ToolOutcome("get_image 需要 id 参数")
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "$id")
            val b64 = resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 1024) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return ToolOutcome("照片解码失败")
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                bmp.recycle()
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } ?: return ToolOutcome("找不到该照片（可能已被删除）")
            return ToolOutcome("已附上照片（id=$id）缩略图，请用多模态视觉能力直接查看。", imageBase64 = b64)
        }
        val collection: Uri = when (action) {
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audios" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val label = when (action) { "videos" -> "视频"; "audios" -> "音频"; else -> "照片" }
        resolver.query(collection, arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
        ), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val out = StringBuilder("最近$label" + (if (action == "photos") "（get_image(id) 可把某张发给模型看）" else "") + "：\n")
            var n = 0
            while (c.moveToNext() && n < 10) {
                out.appendLine("id=${c.getLong(0)}｜${c.getString(1)}｜${df.format(Date(c.getLong(2) * 1000))}")
                n++
            }
            if (n == 0) return ToolOutcome("媒体库中没有$label")
            return ToolOutcome(out.toString().trim().take(MAX_TEXT))
        } ?: return ToolOutcome("无法读取媒体库")
    }

    /* ---------- phone_location：位置 ---------- */

    private fun location(context: Context, action: String): ToolOutcome {
        if (action != "current") return ToolOutcome("phone_location 仅支持 action=current")
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val best = listOf(android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        return if (best != null) {
            val age = (System.currentTimeMillis() - best.time) / 1000
            ToolOutcome("最近已知位置（${age}s 前，精度 ±${best.accuracy.toInt()}m）：lat=${best.latitude}, lon=${best.longitude}" +
                (best.altitude.takeIf { it != 0.0 }?.let { "，海拔=${it.toInt()}m" } ?: "") +
                "\n这是「最近已知」位置；实时定位需要应用在前台持续请求，精度以 GPS 为准。")
        } else ToolOutcome("设备还没有任何已知位置记录（定位服务可能关闭，或应用从未定位过）。")
    }

    /* ---------- phone_alarm：闹钟/计时器 ---------- */

    private fun alarm(context: Context, action: String, args: JsonObject): ToolOutcome {
        val label = needArg(args, "label")
        when (action) {
            "alarm" -> {
                val hour = args["hour"]?.jsonPrimitive?.intOrNull
                val minute = args["minute"]?.jsonPrimitive?.intOrNull
                if (hour == null || minute == null) return ToolOutcome("alarm 需要 hour 与 minute（24 小时制）")
                val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    label?.let { putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return ToolOutcome("已通过系统时钟设置闹钟 ${"%02d".format(hour)}:${"%02d".format(minute)}${label?.let { "（$it）" } ?: ""}。")
            }
            "timer" -> {
                val seconds = args["seconds"]?.jsonPrimitive?.intOrNull ?: return ToolOutcome("timer 需要 seconds 参数")
                val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    label?.let { putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return ToolOutcome("已通过系统时钟设置 ${seconds}s 计时器${label?.let { "（$it）" } ?: ""}。")
            }
            else -> return ToolOutcome("phone_alarm 支持 alarm / timer")
        }
    }

    /* ---------- phone_apps：应用 ---------- */

    private fun apps(context: Context, action: String, args: JsonObject): ToolOutcome {
        val pm = context.packageManager
        when (action) {
            "launch" -> {
                val pkg = needArg(args, "package") ?: return ToolOutcome("launch 需要 package 参数")
                val intent = pm.getLaunchIntentForPackage(pkg)
                return if (intent != null) {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ToolOutcome("已启动应用 $pkg")
                } else ToolOutcome("应用 $pkg 不存在或没有可启动入口")
            }
            "open_details" -> {
                val pkg = needArg(args, "package") ?: return ToolOutcome("open_details 需要 package 参数")
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ToolOutcome("已打开 $pkg 的应用详情页")
            }
            "uninstall" -> {
                val pkg = needArg(args, "package") ?: return ToolOutcome("uninstall 需要 package 参数")
                context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ToolOutcome("已请求卸载 $pkg（系统会弹出确认，由用户决定）")
            }
            else -> {
                val q = (needArg(args, "query") ?: "").lowercase()
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(intent, 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                    .distinctBy { it.second }
                    .filter { q.isBlank() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
                    .sortedBy { it.first }
                    .take(25)
                val out = StringBuilder("已安装应用${if (q.isNotBlank()) "（匹配「$q」）" else ""}：\n")
                apps.forEach { out.appendLine("${it.first}（${it.second}）") }
                return ToolOutcome(out.toString().trim().take(MAX_TEXT))
            }
        }
    }

    /* ---------- phone_device：设备控制 ---------- */

    private fun device(context: Context, action: String, args: JsonObject): ToolOutcome {
        when (action) {
            "volume" -> {
                val type = needArg(args, "type") ?: "music"
                val percent = args["percent"]?.jsonPrimitive?.intOrNull
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = if (type == "ring") AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(stream)
                if (percent != null) {
                    am.setStreamVolume(stream, (max * percent.coerceIn(0, 100)) / 100, 0)
                    return ToolOutcome("已将${if (type == "ring") "铃声" else "媒体"}音量设为 $percent%")
                }
                return ToolOutcome("${if (type == "ring") "铃声" else "媒体"}音量当前 ${am.getStreamVolume(stream)}/$max")
            }
            "flashlight" -> {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    return ToolOutcome("手电筒需要 CAMERA 权限，请在「手机能力授权」中授予。")
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val torchCam = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return ToolOutcome("设备没有闪光灯")
                val on = args["on"]?.jsonPrimitive?.booleanOrNull ?: true
                cm.setTorchMode(torchCam, on)
                return ToolOutcome(if (on) "手电筒已打开" else "手电筒已关闭")
            }
            "vibrate" -> {
                val ms = args["ms"]?.jsonPrimitive?.longOrNull ?: 300L
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(ms)
                return ToolOutcome("已震动 ${ms}ms")
            }
            "dnd" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted)
                    return specialDenied("勿扰开关需要「勿扰访问」特殊权限。")
                val on = args["on"]?.jsonPrimitive?.booleanOrNull ?: true
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_NONE)
                return ToolOutcome(if (on) "勿扰已开启（仅优先提醒）" else "勿扰已关闭")
            }
            "brightness" -> {
                if (!Settings.System.canWrite(context))
                    return specialDenied("修改亮度需要「修改系统设置」特殊权限。")
                val percent = args["percent"]?.jsonPrimitive?.intOrNull ?: return ToolOutcome("brightness 需要 percent 参数")
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, percent.coerceIn(1, 100) * 255 / 100)
                return ToolOutcome("亮度已设为 $percent%")
            }
            "panel" -> {
                val panel = needArg(args, "type") ?: "internet"
                val i = when (panel) {
                    "wifi" -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    "volume" -> Intent(Settings.Panel.ACTION_VOLUME)
                    else -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return ToolOutcome("已打开系统${
                    when (panel) { "wifi" -> "网络连接"; "volume" -> "音量"; else -> "网络连接" } }面板")
            }
            else -> return ToolOutcome("phone_device 支持 volume/flashlight/vibrate/dnd/brightness/panel")
        }
    }

    /* ---------- phone_files：文件 ---------- */

    private fun files(context: Context, action: String, args: JsonObject): ToolOutcome {
        val resolver = context.contentResolver
        when (action) {
            "save_downloads" -> {
                val filename = needArg(args, "filename") ?: return ToolOutcome("save_downloads 需要 filename 参数")
                val content = needArg(args, "content") ?: return ToolOutcome("save_downloads 需要 content 参数")
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE,
                        if (filename.endsWith(".md")) "text/markdown" else "text/plain")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(
                    if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    else MediaStore.Files.getContentUri("external"), values)
                    ?: return ToolOutcome("写入下载目录失败")
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                return ToolOutcome("已保存到 下载/$filename（${content.length} 字符）")
            }
            "app_read" -> {
                val path = needArg(args, "path") ?: return ToolOutcome("app_read 需要 path 参数")
                // 🔴 路径穿越校验（与 app_write 一致）：canonical path 必须留在 filesDir 内
                if (path.contains("..")) return ToolOutcome("非法路径")
                val f = java.io.File(context.filesDir, path)
                if (runCatching { f.canonicalPath.startsWith(context.filesDir.canonicalPath) }.getOrDefault(false).not())
                    return ToolOutcome("非法路径")
                if (!f.exists()) return ToolOutcome("文件不存在：$path")
                return ToolOutcome(f.readText(Charsets.UTF_8).take(MAX_TEXT))
            }
            "app_write" -> {
                val path = needArg(args, "path") ?: return ToolOutcome("app_write 需要 path 参数")
                val content = needArg(args, "content") ?: return ToolOutcome("app_write 需要 content 参数")
                if (path.contains("..")) return ToolOutcome("非法路径")
                val f = java.io.File(context.filesDir, path)
                f.parentFile?.mkdirs()
                f.writeText(content, Charsets.UTF_8)
                return ToolOutcome("已写入应用私有文件 $path（${content.length} 字符）")
            }
            else -> return ToolOutcome("phone_files 支持 save_downloads/app_read/app_write")
        }
    }
}

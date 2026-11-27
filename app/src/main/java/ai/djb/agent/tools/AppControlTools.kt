package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import android.content.Intent
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject

class AppControlTools {

    class LaunchApp : AgentTool {
        override val spec = ToolSpec(
            name = "launch_app",
            description = "افتح تطبيقًا بالاسم أو باسم الحزمة (مثل com.whatsapp). استخدم list_apps إن لم تعرف الاسم.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("package", JSONObject().put("type", "string").put("description", "اسم الحزمة"))
                    .put("name", JSONObject().put("type", "string").put("description", "أو جزء من اسم التطبيق")))
                .put("required", org.json.JSONArray().put("package")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val pm = ctx.app.packageManager
            var pkg = args.optString("package").trim()
            val name = args.optString("name").trim()

            if (pkg.isBlank() && name.isNotBlank()) {
                val found = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().contains(name, ignoreCase = true)
                } ?: return "ERROR: لم أجد تطبيقًا باسم '$name'. جرّب list_apps."
                pkg = found.packageName
            }

            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return "ERROR: '$pkg' غير مثبّت أو ليس له واجهة تشغيل."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح $pkg"
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class SendSms : AgentTool {
        override val spec = ToolSpec(
            name = "compose_sms",
            description = "افتح تطبيق الرسائل مع رقم ونص جاهزين. لا يُرسل تلقائيًا؛ المستخدم يضغط إرسال.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("number", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("number")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val number = args.optString("number").trim()
            if (number.isBlank()) return "ERROR: number مطلوب."
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:$number")
                putExtra("sms_body", args.optString("text"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح الرسائل إلى $number (الإرسال بيد المستخدم)."
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class ComposeEmail : AgentTool {
        override val spec = ToolSpec(
            name = "compose_email",
            description = "افتح تطبيق البريد مع المرسل إليه والموضوع والنص. لا يُرسل تلقائيًا.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("to", JSONObject().put("type", "string"))
                    .put("subject", JSONObject().put("type", "string"))
                    .put("body", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("to")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val to = args.optString("to").trim()
            if (to.isBlank()) return "ERROR: to مطلوب."
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, args.optString("subject"))
                putExtra(Intent.EXTRA_TEXT, args.optString("body"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح البريد إلى $to (الإرسال بيد المستخدم)."
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class Dial : AgentTool {
        override val spec = ToolSpec(
            name = "dial",
            description = "افتح لوحة الاتصال برقم جاهز (لا يتصل تلقائيًا).",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("number", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("number")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val number = args.optString("number").trim()
            if (number.isBlank()) return "ERROR: number مطلوب."
            val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح لوحة الاتصال بـ $number."
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class SetAlarm : AgentTool {
        override val spec = ToolSpec(
            name = "set_alarm",
            description = "اضبط منبهًا على الساعة والدقيقة (24 ساعة).",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("hour", JSONObject().put("type", "integer"))
                    .put("minute", JSONObject().put("type", "integer"))
                    .put("label", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("hour").put("minute")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val hour = args.optInt("hour", -1)
            val minute = args.optInt("minute", -1)
            if (hour !in 0..23 || minute !in 0..59) return "ERROR: وقت غير صالح."
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "djb ai"))
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                ctx.app.startActivity(intent)
                "تم ضبط منبه %02d:%02d.".format(hour, minute)
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class SetTimer : AgentTool {
        override val spec = ToolSpec(
            name = "set_timer",
            description = "ابدأ مؤقتًا لعدد ثوانٍ.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("seconds", JSONObject().put("type", "integer")))
                .put("required", org.json.JSONArray().put("seconds")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val seconds = args.optInt("seconds", 0)
            if (seconds <= 0) return "ERROR: seconds يجب أن يكون أكبر من صفر."
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                ctx.app.startActivity(intent)
                "بدأ مؤقت $seconds ثانية."
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class MediaControl : AgentTool {
        override val spec = ToolSpec(
            name = "media_control",
            description = "تحكم في تشغيل الوسائط: play, pause, next, previous.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("action", prop("string", "إجراء الوسائط", listOf("play", "pause", "next", "previous"))))
                .put("required", org.json.JSONArray().put("action")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val key = when (args.optString("action")) {
                "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return "ERROR: إجراء غير معروف."
            }
            val am = ctx.app.getSystemService(android.media.AudioManager::class.java)
            return runCatching {
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                "تم تنفيذ ${args.optString("action")}."
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class SetVolume : AgentTool {
        override val spec = ToolSpec(
            name = "set_volume",
            description = "اضبط مستوى الصوت. stream: music, ring, alarm, notification. level: 0..15.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("stream", JSONObject().put("type", "string"))
                    .put("level", JSONObject().put("type", "integer")))
                .put("required", org.json.JSONArray().put("stream").put("level")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val stream = when (args.optString("stream")) {
                "music" -> android.media.AudioManager.STREAM_MUSIC
                "ring" -> android.media.AudioManager.STREAM_RING
                "alarm" -> android.media.AudioManager.STREAM_ALARM
                "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
                else -> return "ERROR: stream غير معروف."
            }
            val am = ctx.app.getSystemService(android.media.AudioManager::class.java)
            val max = am.getStreamMaxVolume(stream)
            val level = args.optInt("level", 0).coerceIn(0, max)
            am.setStreamVolume(stream, level, 0)
            return "تم ضبط الصوت على $level/$max."
        }
    }

    class ToggleSetting : AgentTool {
        override val spec = ToolSpec(
            name = "toggle_setting",
            description = "شغّل/أطفئ البلوتوث أو واي فاي. على أندرويد 13+ تُفتح صفحة الإعدادات بدل التبديل المباشر.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("target", prop("string", "ما يُفتح", listOf("wifi", "bluetooth", "airplane")))
                    .put("enable", JSONObject().put("type", "boolean")))
                .put("required", org.json.JSONArray().put("target").put("enable")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val enable = args.optBoolean("enable")
            val action = when (args.optString("target")) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                else -> return "ERROR: target غير معروف."
            }
            ctx.app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "فتحت صفحة الإعدادات؛ التبديل الفعلي يحتاج لمسة منك (قيود أندرويد). المطلوب: ${if (enable) "تشغيل" else "إطفاء"}."
        }
    }

    class CapturePhoto : AgentTool {
        override val spec = ToolSpec(
            name = "capture_photo",
            description = "افتح الكاميرا لالتقاط صورة. الصورة يحفظها تطبيق الكاميرا عند تأكيدك.",
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح الكاميرا."
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class PickContact : AgentTool {
        override val spec = ToolSpec(
            name = "open_contacts",
            description = "افتح تطبيق جهات الاتصال أو صفحة إضافة جهة جديدة.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("mode", prop("string", "list أو insert", listOf("list", "insert"))))
                .put("required", org.json.JSONArray().put("mode")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val action = if (args.optString("mode") == "insert") {
                ContactsContract.Intents.Insert.ACTION
            } else {
                Intent.ACTION_VIEW
            }
            val intent = Intent(action).apply {
                if (action == Intent.ACTION_VIEW) {
                    data = ContactsContract.Contacts.CONTENT_URI
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح جهات الاتصال."
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }
}

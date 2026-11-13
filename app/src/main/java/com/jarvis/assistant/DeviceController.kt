package com.jarvis.assistant

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import java.io.File
import java.util.Locale

class DeviceController(private val context: Context) {

    data class ActionOutcome(
        val message: String,
        val requiresRebootConfirm: Boolean = false
    )

    fun execute(
        action: String,
        params: Map<String, String>,
        rootAllowed: Boolean
    ): ActionOutcome {
        return when (action.uppercase(Locale.US)) {
            "NONE" -> ActionOutcome("")
            "FLASHLIGHT_ON" -> ActionOutcome(setFlashlight(true))
            "FLASHLIGHT_OFF" -> ActionOutcome(setFlashlight(false))
            "GET_BATTERY_INFO" -> ActionOutcome(getBatteryInfo())
            "GET_DEVICE_STATS" -> ActionOutcome(getDeviceStats(rootAllowed))
            "OPEN_APP" -> ActionOutcome(openApp(params["app_name"] ?: ""))
            "SET_VOLUME" -> ActionOutcome(adjustVolume(params["mode"] ?: "up"))
            "SET_ALARM" -> ActionOutcome(
                setAlarm(
                    params["hour"]?.toIntOrNull() ?: 7,
                    params["minute"]?.toIntOrNull() ?: 0,
                    params["label"] ?: "JARVIS Alarm"
                )
            )
            "DIAL_NUMBER" -> ActionOutcome(dialNumber(params["number"] ?: ""))
            "LOCK_SCREEN" -> ActionOutcome(lockScreen(rootAllowed))
            "TAKE_SCREENSHOT" -> ActionOutcome(takeScreenshot(rootAllowed))
            "WIFI_ON" -> ActionOutcome(toggleWifi(true, rootAllowed))
            "WIFI_OFF" -> ActionOutcome(toggleWifi(false, rootAllowed))
            "DATA_ON" -> ActionOutcome(toggleData(true, rootAllowed))
            "DATA_OFF" -> ActionOutcome(toggleData(false, rootAllowed))
            "BLUETOOTH_ON" -> ActionOutcome(toggleBluetooth(true, rootAllowed))
            "BLUETOOTH_OFF" -> ActionOutcome(toggleBluetooth(false, rootAllowed))
            "REBOOT_CONFIRM" -> ActionOutcome(
                if (rootAllowed && RootExecutor.isRootAvailable()) {
                    "Awaiting your confirmation to reboot, Sir."
                } else {
                    "Root access is required to reboot the device, Sir."
                },
                requiresRebootConfirm = rootAllowed && RootExecutor.isRootAvailable()
            )
            else -> ActionOutcome("")
        }
    }

    private fun setFlashlight(enable: Boolean): String {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList.firstOrNull { id ->
                camManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight hardware detected."
            camManager.setTorchMode(cameraId, enable)
            if (enable) "Flashlight enabled." else "Flashlight disabled."
        } catch (e: Exception) {
            "Flashlight error: ${e.message}"
        }
    }

    private fun getBatteryInfo(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (scale > 0) (level * 100) / scale else level
        return "Battery: $pct% (${if (isCharging) "Charging" else "Discharging"}), Temp: ${temp}°C"
    }

    private fun getDeviceStats(rootAllowed: Boolean): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availGb = String.format(Locale.US, "%.2f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
        val totalGb = String.format(Locale.US, "%.2f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
        val battery = getBatteryInfo()

        var cpuInfo = ""
        if (rootAllowed && RootExecutor.isRootAvailable()) {
            val (ok, tempStr) = RootExecutor.run("cat /sys/class/thermal/thermal_zone0/temp")
            if (ok) {
                val raw = tempStr.toFloatOrNull()
                if (raw != null) {
                    val c = if (raw > 1000) raw / 1000f else raw
                    cpuInfo = ", CPU Zone0: ${String.format(Locale.US, "%.1f", c)}°C"
                }
            }
        } else {
            val thermalFile = File("/sys/class/thermal/thermal_zone0/temp")
            if (thermalFile.canRead()) {
                val raw = thermalFile.readText().trim().toFloatOrNull()
                if (raw != null) {
                    val c = if (raw > 1000) raw / 1000f else raw
                    cpuInfo = ", Thermal: ${String.format(Locale.US, "%.1f", c)}°C"
                }
            }
        }
        return "$battery | RAM Free: $availGb / $totalGb GB$cpuInfo"
    }

    private fun openApp(appName: String): String {
        if (appName.isBlank()) return "Which app should I open, Sir?"
        val pm = context.packageManager
        val query = appName.lowercase(Locale.US).trim()
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val matched = apps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase(Locale.US)
            label == query || label.contains(query)
        }
        return if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Launched ${pm.getApplicationLabel(matched)}."
            } else {
                "Cannot launch ${matched.packageName}."
            }
        } else {
            "App '$appName' not found on device."
        }
    }

    private fun adjustVolume(mode: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (mode.lowercase(Locale.US)) {
            "up", "increase" -> am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            "down", "decrease" -> am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            "mute" -> am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI
            )
            "unmute" -> am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI
            )
        }
        return "Volume adjusted ($mode)."
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            String.format(Locale.US, "Alarm setup opened for %02d:%02d.", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        } catch (e: Exception) {
            "Could not set alarm: ${e.message}"
        }
    }

    private fun dialNumber(number: String): String {
        val clean = number.replace("[^0-9+*#]".toRegex(), "")
        if (clean.isBlank()) return "Please provide a valid phone number."
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opening dialer for $clean."
    }

    private fun lockScreen(rootAllowed: Boolean): String {
        val acc = JarvisAccessibilityService.instance
        if (acc != null && acc.lockScreen()) {
            return "Screen locked via Accessibility."
        }
        if (rootAllowed && RootExecutor.isRootAvailable()) {
            val (ok, _) = RootExecutor.run("input keyevent 26")
            if (ok) return "Screen locked via Root."
        }
        return "Enable JARVIS Accessibility Service (or Root) in Settings to lock screen."
    }

    private fun takeScreenshot(rootAllowed: Boolean): String {
        val acc = JarvisAccessibilityService.instance
        if (acc != null && acc.takeScreenshotAction()) {
            return "Screenshot captured."
        }
        if (rootAllowed && RootExecutor.isRootAvailable()) {
            val path = "/sdcard/Pictures/jarvis_${System.currentTimeMillis()}.png"
            val (ok, _) = RootExecutor.run("screencap -p $path")
            if (ok) return "Screenshot saved via Root to $path"
        }
        return "Enable JARVIS Accessibility Service (or Root) in Settings for screenshots."
    }

    private fun toggleWifi(enable: Boolean, rootAllowed: Boolean): String {
        if (rootAllowed && RootExecutor.isRootAvailable()) {
            val cmd = if (enable) "svc wifi enable" else "svc wifi disable"
            val (ok, out) = RootExecutor.run(cmd)
            return if (ok) "Wi-Fi ${if (enable) "enabled" else "disabled"} via Root." else "Root Wi-Fi error: $out"
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened Wi-Fi panel (Grant Root for direct toggle)."
    }

    private fun toggleData(enable: Boolean, rootAllowed: Boolean): String {
        if (rootAllowed && RootExecutor.isRootAvailable()) {
            val cmd = if (enable) "svc data enable" else "svc data disable"
            val (ok, out) = RootExecutor.run(cmd)
            return if (ok) "Mobile data ${if (enable) "enabled" else "disabled"} via Root." else "Root data error: $out"
        }
        val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opened Network settings (Grant Root for direct mobile data toggle)."
    }

    private fun toggleBluetooth(enable: Boolean, rootAllowed: Boolean): String {
        if (rootAllowed && RootExecutor.isRootAvailable()) {
            val cmd = if (enable) "cmd bluetooth_manager enable" else "cmd bluetooth_manager disable"
            val (ok, out) = RootExecutor.run(cmd)
            return if (ok) "Bluetooth ${if (enable) "enabled" else "disabled"} via Root." else "Root BT error: $out"
        }
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opened Bluetooth settings (Grant Root for direct toggle)."
    }
}

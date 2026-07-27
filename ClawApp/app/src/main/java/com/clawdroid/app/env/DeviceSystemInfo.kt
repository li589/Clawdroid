package com.clawdroid.app.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File

/**
 * Device / OEM system labels and lightweight SELinux / network probes
 * that do not require Root.
 */
object DeviceSystemInfo {
    fun systemVersionLabel(): String {
        val androidPart = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val oem = oemRomLabel()
        val model = listOf(Build.BRAND, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifBlank { Build.DEVICE.orEmpty() }
        return buildString {
            if (oem.isNotBlank()) {
                append(oem)
                append(" · ")
            }
            append(androidPart)
            if (model.isNotBlank()) {
                append(" · ")
                append(model)
            }
        }
    }

    fun oemRomLabel(): String {
        val hyperOs = firstNonBlank(
            systemProperty("ro.mi.os.version.name"),
            systemProperty("ro.mi.os.version.code")
        )
        if (hyperOs.isNotBlank()) {
            val ver = systemProperty("ro.mi.os.version.incremental").ifBlank {
                systemProperty("ro.build.version.incremental")
            }
            return if (ver.isNotBlank()) "HyperOS $hyperOs ($ver)" else "HyperOS $hyperOs"
        }
        val miui = systemProperty("ro.miui.ui.version.name")
        if (miui.isNotBlank()) {
            val ver = systemProperty("ro.build.version.incremental")
            return if (ver.isNotBlank()) "MIUI $miui ($ver)" else "MIUI $miui"
        }
        val oneUi = firstNonBlank(
            systemProperty("ro.build.version.oneui"),
            systemProperty("ro.build.version.sep")
        )
        if (oneUi.isNotBlank()) {
            return "One UI $oneUi"
        }
        val colorOs = firstNonBlank(
            systemProperty("ro.oppo.theme.version"),
            systemProperty("ro.build.version.opporom"),
            systemProperty("ro.oplus.version")
        )
        if (colorOs.isNotBlank() || isBrand("oppo", "realme", "oneplus")) {
            val label = colorOs.ifBlank { systemProperty("ro.build.display.id") }
            if (label.isNotBlank()) return "ColorOS/Oxygen $label"
        }
        val harmony = systemProperty("hw_sc.build.platform.version")
        if (harmony.isNotBlank() || isBrand("huawei", "honor")) {
            val emui = systemProperty("ro.build.version.emui")
            return when {
                harmony.isNotBlank() -> "HarmonyOS $harmony"
                emui.isNotBlank() -> "EMUI $emui"
                else -> "Harmony/EMUI"
            }
        }
        val flyme = systemProperty("ro.build.flyme.version")
        if (flyme.isNotBlank() || isBrand("meizu")) {
            return "Flyme ${flyme.ifBlank { systemProperty("ro.build.display.id") }}"
        }
        val originOs = systemProperty("ro.vivo.os.version")
        if (originOs.isNotBlank() || isBrand("vivo", "iqoo")) {
            return "OriginOS/Funtouch $originOs".trim()
        }
        val display = Build.DISPLAY.orEmpty().trim()
        return if (display.isNotBlank() && !display.equals(Build.VERSION.RELEASE, ignoreCase = true)) {
            "AOSP/定制 $display"
        } else {
            "AOSP"
        }
    }

    /** true = Enforcing, false = Permissive/Disabled, null = unknown */
    fun selinuxEnforcing(): Boolean? {
        runCatching {
            val file = File("/sys/fs/selinux/enforce")
            if (file.canRead()) {
                return when (file.readText().trim()) {
                    "1" -> true
                    "0" -> false
                    else -> null
                }
            }
        }
        return runCatching {
            val process = ProcessBuilder("getenforce")
                .redirectErrorStream(true)
                .start()
            try {
                if (!process.waitFor(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroy()
                    return@runCatching null
                }
                val output = process.inputStream.bufferedReader().readText().trim()
                when {
                    output.equals("Enforcing", ignoreCase = true) -> true
                    output.equals("Permissive", ignoreCase = true) -> false
                    output.equals("Disabled", ignoreCase = true) -> false
                    else -> null
                }
            } finally {
                process.destroy()
            }
        }.getOrNull()
    }

    fun networkConnected(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching false
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        }.getOrDefault(false)
    }

    private fun isBrand(vararg names: String): Boolean {
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        return names.any { brand.contains(it) || manufacturer.contains(it) }
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun systemProperty(key: String): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            (method.invoke(null, key, "") as? String).orEmpty().trim()
        }.getOrDefault("")
    }
}

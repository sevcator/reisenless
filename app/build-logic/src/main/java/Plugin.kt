
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.provideDelegate
import java.io.File
import java.util.Properties



val ABI_SUPPORT_LIST = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64")

private val props = Properties()

object Config {
    operator fun get(key: String): String? {
        val v = props[key] as? String ?: return null
        return v.ifBlank { null }
    }

    fun contains(key: String) = get(key) != null

    // Properties from config.prop and flags.prop, may be null
    val version: String get() = get("version") ?: "null"
    val abiList: List<String> get() = get("abiList")?.split(",") ?: ABI_SUPPORT_LIST
    val toolAbiList: List<String> get() =
        (get("toolAbiList") ?: get("abiList"))?.split(",") ?: abiList
    val secureDir: String get() = get("secureDir") ?: "/data/adb"
    val appPackageName: String get() = get("appPackageName") ?: "io.sevcator.reisenless"
    val classNamespace: String get() = get("classNamespace") ?: "com.topjohnwu.magisk"
    val sharedNamespace: String get() = get("sharedNamespace") ?: "com.topjohnwu.shared"
    val superuserNamespace: String get() = get("superuserNamespace") ?: "com.topjohnwu.superuser"
    val widgetNamespace: String get() = get("widgetNamespace") ?: "com.topjohnwu.widget"
    val vendorNamespace: String get() = get("vendorNamespace") ?: "com.topjohnwu"
    val appLabel: String get() = get("appLabel") ?: "reisenless"
    val appVersionName: String get() = get("appVersionName") ?: version
    val brandLong: String get() = get("brandLong") ?: "rootclient"
    val brandCore: String get() = get("brandCore") ?: "kernel"
    val brandInject: String get() = get("brandInject") ?: "inject"
    val brandAuthor: String get() = get("brandAuthor") ?: "developer"
    val appClass: String get() = get("appClass") ?: "com.topjohnwu.magisk.core.App"
    val mainActivityClass: String get() = get("mainActivityClass") ?: "com.topjohnwu.magisk.ui.MainActivity"
    val suRequestActivityClass: String get() = get("suRequestActivityClass") ?: "com.topjohnwu.magisk.ui.surequest.SuRequestActivity"
    val webUiActivityClass: String get() = get("webUiActivityClass") ?: "com.topjohnwu.magisk.ui.webui.WebUIActivity"
    val receiverClass: String get() = get("receiverClass") ?: "com.topjohnwu.magisk.core.Receiver"
    val serviceClass: String get() = get("serviceClass") ?: "com.topjohnwu.magisk.core.Service"
    val jobServiceClass: String get() = get("jobServiceClass") ?: "com.topjohnwu.magisk.core.JobService"
    val backgroundUpdateJobServiceClass: String get() = get("backgroundUpdateJobServiceClass") ?: "com.topjohnwu.magisk.core.BackgroundUpdateJobService"
    val providerClass: String get() = get("providerClass") ?: "com.topjohnwu.magisk.core.Provider"
    val anchorSuffix: String get() = get("anchorSuffix") ?: "anchor"
    val providerSuffix: String get() = get("providerSuffix") ?: "provider"
    val mainBinName: String get() = get("buildId") ?: "ms"
    val dataDir: String get() = get("dataDir") ?: "ms"
    val dbName: String get() = get("dbName") ?: "ms.db"
    val internalDir: String get() = get("internalDir") ?: ".ms"
    val socketName: String get() = get("socketName") ?: "socket"
    val policyName: String get() = get("policyName") ?: "mpol"
    val bin32Name: String get() = get("bin32Name") ?: "ms32"
    val busyboxName: String get() = get("busyboxName") ?: "busybox"
    val mainLibName: String get() = get("mainLibName") ?: "magisk"
    val busyboxLibName: String get() = get("busyboxLibName") ?: "busybox"
    val policyLibName: String get() = get("policyLibName") ?: "mpol"
    val initLdLibName: String get() = get("initLdLibName") ?: "init-ld"
    val bootLibName: String get() = get("bootLibName") ?: "mboot"
    val initLibName: String get() = get("initLibName") ?: "minit"
    val bootctlLibName: String get() = get("bootctlLibName") ?: "bootctl"
    val ramdiskName: String get() = get("ramdiskName") ?: "ms"
    val stubName: String get() = get("stubName") ?: "stub.apk"
    val initLdName: String get() = get("initLdName") ?: "init-ld"
    val udongeDir: String get() = get("udongeDir") ?: "udonge"
    val udongeArchive: String get() = get("udongeArchive") ?: "udonge.bin"
    val backupConfig: String get() = get("backupConfig") ?: ".cfg"
    val redirPath: String get() = get("redirPath") ?: "/data/._init"
    val suCache: String get() = get("suCache") ?: ".su_cache"
    val tmpDir: String get() = get("tmpDir") ?: "/dev/tmp"
    val backupPrefix: String get() = get("backupPrefix") ?: "/data/ms_backup_"
    val stageScript: String get() = get("stageScript") ?: "udonge.sh"
    val legacySecureDir: String get() = get("legacySecureDir") ?: ""
    val legacyDbName: String get() = get("legacyDbName") ?: ""
    val legacyUdongeDir: String get() = get("legacyUdongeDir") ?: ""
    val legacyBackupConfig: String get() = get("legacyBackupConfig") ?: ""

    // Properties from gradle.properties, should always exist
    val versionCode: Int get() = get("magisk.versionCode")!!.toInt()
    val stubVersion: String get() = get("magisk.stubVersion")!!
}

fun Project.rootFile(path: String): File {
    val file = File(path)
    return if (file.isAbsolute) file
    else File(rootProject.file(".."), path)
}

class MagiskPlugin : Plugin<Project> {
    override fun apply(project: Project) = project.applyPlugin()

    private fun Project.applyPlugin() {
        props.clear()

        // Get gradle properties relevant to Magisk
        props.putAll(providers.gradlePropertiesPrefixedBy("magisk.").get())

        // Load config.prop
        val configPath = findProperty("configPath") as String?
        val configFile = rootFile(configPath ?: "config.prop")
        if (configFile.exists()) {
            configFile.inputStream().use {
                val config = Properties()
                config.load(it)
                props.putAll(config)
            }
        }


        val flagsProp = rootProject.layout.buildDirectory.file("flags.prop").get().asFile
        if (flagsProp.exists()) {
            flagsProp.inputStream().use {
                val flags = Properties()
                flags.load(it)
                props.putAll(flags)
            }
        }
    }
}

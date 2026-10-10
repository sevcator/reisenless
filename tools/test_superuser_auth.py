
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import tomllib

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"

def jar(group, name, version=None):
    base = CACHE / group / name
    if version is None and base.exists():
        available = [path for path in base.iterdir() if path.is_dir() and list(path.rglob("*.jar"))]
        base = max(available, key=lambda path: tuple(int(part) for part in path.name.split(".")))
    candidates = list((base / version if version else base).rglob("*.jar"))
    if not candidates:
        raise RuntimeError(f"Missing cached dependency {group}:{name}; run app Gradle dependency resolution first")
    return max(candidates, key=lambda path: path.stat().st_mtime)

SOURCES = {
    "Android.kt": """
        package android.annotation
        annotation class SuppressLint(vararg val value: String)
    """,
    "Process.kt": """
        package android.os
        object Process { const val SYSTEM_UID = 1000 }
    """,
    "Packages.kt": """
        package android.content.pm
        class ApplicationInfo { val uid = 12345 }
        class PackageInfo {
            val packageName = "com.example.target"
            val sharedUserId: String? = null
            val applicationInfo: ApplicationInfo? = null
        }
        object PackageManager {
            const val MATCH_UNINSTALLED_PACKAGES = 8192
            class NameNotFoundException : Exception()
            fun getPackagesForUid(uid: Int): Array<String>? = null
            fun getPackageInfo(pkg: String, flags: Int) = PackageInfo()
        }
    """,
    "Lifecycle.kt": """
        package androidx.lifecycle
        import com.topjohnwu.magisk.arch.AsyncLoadViewModel
        import kotlinx.coroutines.CoroutineScope
        val AsyncLoadViewModel.viewModelScope: CoroutineScope get() = scope
    """,
    "ViewModel.kt": """
        package com.topjohnwu.magisk.arch
        import kotlinx.coroutines.*
        abstract class AsyncLoadViewModel {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            abstract suspend fun doLoadWork()
            fun reload() {}
            fun showSnackbar(text: String) {}
        }
    """,
    "Core.kt": """
        package com.topjohnwu.magisk.core
        import android.content.pm.*
        object Config { var suAuth = false; val suRestrict = false }
        object Info { val showSuperUser = false }
        object AppContext {
            val applicationInfo = ApplicationInfo()
            val packageManager = PackageManager
            val packageName = "com.example.manager"
            fun getString(id: Int, vararg args: Any) = "message"
        }
        object R { object string {
            const val su_snack_grant = 1
            const val su_snack_notif_on = 2
            const val su_snack_notif_off = 3
            const val su_snack_deny = 4
        } }
    """,
    "Labels.kt": """
        package com.topjohnwu.magisk.core.ktx
        import android.content.pm.*
        fun ApplicationInfo.getLabel(pm: PackageManager) = "target"
    """,
    "Database.kt": """
        package com.topjohnwu.magisk.core.data.magiskdb
        import com.topjohnwu.magisk.core.model.su.SuPolicy
        import java.util.Collections
        class MagiskDB { class Literal(val value: String) }
        class PolicyDao {
            val written = Collections.synchronizedList(mutableListOf<SuPolicy>())
            suspend fun update(policy: SuPolicy) { written.add(policy) }
            suspend fun delete(uid: Int) {}
            suspend fun deleteOutdated() {}
            suspend fun fetchAll() = emptyList<SuPolicy>()
        }
    """,
    "Events.kt": """
        package com.topjohnwu.magisk.core.su
        import kotlinx.coroutines.flow.emptyFlow
        object SuEvents { val policyChanged = emptyFlow<Unit>() }
    """,
    "Catalog.kt": """
        package com.topjohnwu.magisk.core.utils
        import kotlinx.coroutines.flow.emptyFlow
        data class InstalledApp(val packageName: String, val label: String, val uid: Int)
        object AppCatalog {
            val generation = emptyFlow<Long>()
            suspend fun apps() = emptyList<InstalledApp>()
        }
    """,
    "AuthRegression.kt": """
        import com.topjohnwu.magisk.core.Config
        import com.topjohnwu.magisk.core.data.magiskdb.PolicyDao
        import com.topjohnwu.magisk.core.model.su.SuPolicy
        import com.topjohnwu.magisk.ui.superuser.*
        import kotlinx.coroutines.*

        fun settle(vm: SuperuserViewModel) = runBlocking {
            vm.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }
        fun main() {
            val target = AddableAppInfo("com.example.target", "Target", 12346)
            Config.suAuth = true
            val database = PolicyDao()
            val vm = SuperuserViewModel(database)
            var requests = 0
            var approve: (() -> Unit)? = null
            vm.authenticate = { success -> requests++; approve = success }
            vm.grantApp(target)
            settle(vm)
            check(requests == 1) { "grantApp bypassed required authentication" }
            check(database.written.isEmpty()) { "grantApp wrote policy before authentication succeeded" }
            approve!!()
            settle(vm)
            check(database.written.size == 1)
            check(database.written.single().uid == 12346)
            check(database.written.single().policy == SuPolicy.ALLOW)
            check(vm.uiState.value.policies.single().packageName == "com.example.target")
            vm.scope.cancel()

            Config.suAuth = false
            val unrestrictedDatabase = PolicyDao()
            val unrestricted = SuperuserViewModel(unrestrictedDatabase)
            unrestricted.authenticate = { error("Unexpected authentication with setting disabled") }
            unrestricted.grantApp(target)
            settle(unrestricted)
            check(unrestrictedDatabase.written.single().policy == SuPolicy.ALLOW)
            unrestricted.scope.cancel()
            println("Superuser grant authentication regression passed")
        }
    """,
}

def compile_and_run(test_sources, production_sources, main_class):
    with (ROOT / "app/gradle/libs.versions.toml").open("rb") as catalog:
        version = tomllib.load(catalog)["versions"]["kotlin"]
    compiler = jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", version)
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", version)
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")
    annotations = jar("org.jetbrains", "annotations")
    compiler_classpath = [compiler, stdlib, coroutines, annotations]
    for name in ("kotlin-script-runtime", "kotlin-reflect", "kotlin-daemon-embeddable"):
        compiler_classpath.append(jar("org.jetbrains.kotlin", name, None if name == "kotlin-reflect" else version))
    trove = CACHE / "org.jetbrains.intellij.deps/trove4j"
    if trove.exists():
        compiler_classpath.append(jar("org.jetbrains.intellij.deps", "trove4j"))
    runtime_classpath = [stdlib, coroutines, annotations]
    with tempfile.TemporaryDirectory(prefix="superuser-auth-") as directory:
        work = Path(directory)
        sources = []
        for name, body in test_sources.items():
            source = work / name
            source.write_text(textwrap.dedent(body), encoding="utf-8")
            sources.append(source)
        sources.extend(production_sources)
        output = work / "classes"
        subprocess.run([
            "java", "-cp", os.pathsep.join(map(str, compiler_classpath)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-classpath", os.pathsep.join(map(str, runtime_classpath)), "-d", str(output),
            *map(str, sources),
        ], check=True)
        subprocess.run([
            "java", "-cp", os.pathsep.join(map(str, [output, *runtime_classpath])), main_class,
        ], check=True)

def main():
    compile_and_run(SOURCES, [
        ROOT / "app/apk/src/main/java/com/topjohnwu/magisk/ui/superuser/SuperuserViewModel.kt",
        ROOT / "app/core/src/main/java/com/topjohnwu/magisk/core/model/su/SuPolicy.kt",
    ], "AuthRegressionKt")

if __name__ == "__main__":
    main()

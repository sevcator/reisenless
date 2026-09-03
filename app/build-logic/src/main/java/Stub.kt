import org.gradle.api.Project
import org.gradle.kotlin.dsl.named
import java.io.File
import java.io.PrintStream
import java.security.SecureRandom
import java.util.Random

fun initRandom(dict: File) {
    RANDOM = if (RAND_SEED != 0) Random(RAND_SEED.toLong()) else SecureRandom()
    val names = mutableListOf<String>()
    for (a in chain('a'..'z', 'A'..'Z')) {
        if (a != 'a' && a != 'A') names.add("$a")
        for (b in chain('a'..'z', 'A'..'Z', '0'..'9')) {
            names.add("$a$b")
            for (c in chain('a'..'z', 'A'..'Z', '0'..'9')) {
                names.add("$a$b$c")
            }
        }
    }
    names.shuffle(RANDOM)
    PrintStream(dict).use { out -> names.forEach(out::println) }
}

private fun <T> chain(vararg iterables: Iterable<T>) = sequence {
    iterables.forEach { iterable -> iterable.forEach { yield(it) } }
}

/** Builds a code-free APK whose signing certificate anchors manager trust. */
fun Project.setupStubApk() {
    setupAppCommon()
    androidAppComponents {
        onVariants { variant ->
            val taskName = "transform${variant.name.replaceFirstChar { it.uppercase() }}Apk"
            tasks.named<TransformApkTask>(taskName).configure {
                transformations.add { apk ->
                    apk.get("classes.dex")?.delete()
                }
            }
        }
    }
}

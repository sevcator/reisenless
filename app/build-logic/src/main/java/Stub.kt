import org.gradle.api.Project
import org.gradle.kotlin.dsl.named

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

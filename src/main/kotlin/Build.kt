import java.util.Properties

/**
 * The build version (served by /version, embedded in index.html and sw.js for self-reload/cache-bust).
 * Set once via the gradle property `appBuild` (gradle.properties, or -PappBuild=...), which
 * processResources substitutes into fpl-build.properties, assets/index.html and assets/sw.js.
 */
val APP_BUILD: String = loadAppBuild()

private fun loadAppBuild(): String {
    val props = Properties()
    object {}.javaClass.getResourceAsStream("/fpl-build.properties")?.use { props.load(it) }
    return props.getProperty("build")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("@") } ?: "dev"
}

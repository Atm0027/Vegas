import org.gradle.api.provider.ProviderFactory

/**
 * Cáscara de [VersionSemantica]: aquí sí se ejecuta git.
 *
 * Solo cuenta commits de Vegas: se excluye todo lo alcanzable desde [baseFork] (el
 * commit de Metrolist del que partió Vegas) y desde `upstream/main` si existe, así
 * que traer arreglos de Metrolist no infla el código ni mueve el nombre.
 */
data class VersionVegas(
    val nombre: String,
    val codigo: Int,
) {
    val etiqueta: String get() = VersionSemantica.etiqueta(nombre, codigo)
}

fun ProviderFactory.versionVegas(baseFork: String): VersionVegas {
    fun git(vararg argumentos: String): String? {
        val salida =
            exec {
                commandLine("git", *argumentos)
                isIgnoreExitValue = true
            }
        return if (salida.result.get().exitValue == 0) salida.standardOutput.asText.get() else null
    }

    // Sin git (código descargado como zip) no hay historia que leer.
    git("rev-parse", "--verify", "--quiet", "HEAD") ?: return VersionVegas("0.0.0-local", 1)

    val excluidos =
        buildList {
            add("^$baseFork")
            if (git("rev-parse", "--verify", "--quiet", "refs/remotes/upstream/main") != null) {
                add("^refs/remotes/upstream/main")
            }
        }

    val codigo =
        git("rev-list", "--count", "HEAD", *excluidos.toTypedArray())
            ?.trim()
            ?.toIntOrNull()
            ?.coerceAtLeast(1) ?: 1

    val etiquetaPrevia =
        git("describe", "--tags", "--abbrev=0", "--match", VersionSemantica.PATRON_DESCRIBE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    val rango = listOfNotNull("HEAD", etiquetaPrevia?.let { "^$it" }) + excluidos
    val mensajes = VersionSemantica.mensajesDelLog(git("log", "--format=%B%x00", *rango.toTypedArray()).orEmpty())

    return VersionVegas(VersionSemantica.siguiente(etiquetaPrevia, mensajes), codigo)
}

/**
 * Qué nombre le toca a la siguiente versión de Vegas, deducido de los commits.
 *
 * Mismo sistema que JARVIS (`core/shell/version_semantica.py`): el nombre sale de
 * los mensajes en formato Conventional Commits desde la última publicación —`feat:`
 * sube la minor, `fix:`/`perf:`/`revert:` la patch, `!` o `BREAKING CHANGE:` la
 * major— y el estado vive en etiquetas de git (`v0.1.0-12`), no en un archivo.
 *
 * Esto es lógica pura y se prueba sin repositorio. Quien llama a git es la cáscara
 * de `app/build.gradle.kts`, que además deja fuera los commits que vienen de
 * Metrolist: la versión de Vegas solo cuenta el trabajo de Vegas.
 */
enum class Salto { NINGUNO, PARCHE, MENOR, MAYOR }

object VersionSemantica {
    /** Etiqueta de publicación: nombre semántico y código de compilación. */
    private val PATRON_ETIQUETA = Regex("""^v(\d+)\.(\d+)\.(\d+)-(\d+)$""")

    /** Cabecera `tipo(ámbito)!: descripción`; ámbito y admiración opcionales. */
    private val PATRON_CABECERA = Regex("""^(?<tipo>[a-zA-Z]+)(?:\([^)]*\))?(?<rompe>!)?:""")

    /** Se aceptan las dos grafías que circulan; perder un aviso rompedor sale caro. */
    private val PATRON_ROMPEDOR = Regex("""^BREAKING[ -]CHANGE:""", RegexOption.MULTILINE)

    const val NOMBRE_INICIAL = "0.1.0"

    /** Lo que no está aquí (`docs`, `chore`, `test`, `build`…) no mueve el nombre. */
    private val SALTOS_POR_TIPO =
        mapOf(
            "feat" to Salto.MENOR,
            "fix" to Salto.PARCHE,
            "perf" to Salto.PARCHE,
            "revert" to Salto.PARCHE,
        )

    /** Patrón para `git describe --match`, a juego con [PATRON_ETIQUETA]. */
    const val PATRON_DESCRIBE = "v*.*.*-*"

    fun nombreDeLaEtiqueta(etiqueta: String): String? {
        val encaje = PATRON_ETIQUETA.matchEntire(etiqueta.trim()) ?: return null
        val (mayor, menor, parche) = encaje.destructured
        return "$mayor.$menor.$parche"
    }

    fun clasificar(mensaje: String): Salto {
        val texto = mensaje.trim()
        if (texto.isEmpty()) return Salto.NINGUNO
        if (PATRON_ROMPEDOR.containsMatchIn(texto)) return Salto.MAYOR

        // Un commit sin formato (un `Merge branch ...`) no decide la versión de nadie.
        val encaje = PATRON_CABECERA.find(texto.lineSequence().first()) ?: return Salto.NINGUNO
        if (encaje.groups["rompe"] != null) return Salto.MAYOR
        return SALTOS_POR_TIPO[encaje.groups["tipo"]!!.value.lowercase()] ?: Salto.NINGUNO
    }

    fun saltoDe(mensajes: Iterable<String>): Salto {
        var mayor = Salto.NINGUNO
        for (mensaje in mensajes) {
            val salto = clasificar(mensaje)
            if (salto > mayor) mayor = salto
            if (mayor == Salto.MAYOR) break
        }
        return mayor
    }

    fun aplicar(
        nombre: String,
        salto: Salto,
    ): String {
        val partes = nombre.trim().split(".")
        require(partes.size == 3 && partes.all { p -> p.isNotEmpty() && p.all(Char::isDigit) }) {
            "«$nombre» no es un nombre semántico de tres partes."
        }
        val (mayor, menor, parche) = partes.map(String::toInt)
        return when (salto) {
            Salto.MAYOR -> "${mayor + 1}.0.0"
            Salto.MENOR -> "$mayor.${menor + 1}.0"
            Salto.PARCHE -> "$mayor.$menor.${parche + 1}"
            Salto.NINGUNO -> "$mayor.$menor.$parche"
        }
    }

    /** Sin etiqueta previa se parte de [NOMBRE_INICIAL]; sin cambios visibles, el nombre no se mueve. */
    fun siguiente(
        etiquetaPrevia: String?,
        mensajes: Iterable<String>,
    ): String {
        val previo = nombreDeLaEtiqueta(etiquetaPrevia.orEmpty()) ?: return NOMBRE_INICIAL
        return aplicar(previo, saltoDe(mensajes))
    }

    fun etiqueta(
        nombre: String,
        codigo: Int,
    ): String = "v$nombre-$codigo"

    /** `git log --format=%B%x00`: se corta por byte nulo para no partir un `BREAKING CHANGE:` de su commit. */
    fun mensajesDelLog(crudo: String): List<String> = crudo.split('\u0000').map(String::trim).filter(String::isNotEmpty)
}

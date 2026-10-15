import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionSemanticaTest {
    @Test
    fun nombreDeLaEtiqueta_leeEtiquetaDePublicacion() {
        assertEquals("1.2.3", VersionSemantica.nombreDeLaEtiqueta("v1.2.3-45"))
    }

    @Test
    fun nombreDeLaEtiqueta_rechazaOtrasEtiquetas() {
        assertNull(VersionSemantica.nombreDeLaEtiqueta("v13.7.0"))
        assertNull(VersionSemantica.nombreDeLaEtiqueta("nightly"))
    }

    @Test
    fun clasificar_tiposConventionalCommits() {
        assertEquals(Salto.MENOR, VersionSemantica.clasificar("feat(playback): algo"))
        assertEquals(Salto.PARCHE, VersionSemantica.clasificar("fix: algo"))
        assertEquals(Salto.PARCHE, VersionSemantica.clasificar("perf(ui): algo"))
        assertEquals(Salto.PARCHE, VersionSemantica.clasificar("revert: algo"))
        assertEquals(Salto.NINGUNO, VersionSemantica.clasificar("docs: algo"))
        assertEquals(Salto.NINGUNO, VersionSemantica.clasificar("build: algo"))
    }

    @Test
    fun clasificar_rompedores() {
        assertEquals(Salto.MAYOR, VersionSemantica.clasificar("feat(api)!: quita algo"))
        assertEquals(Salto.MAYOR, VersionSemantica.clasificar("fix: algo\n\nBREAKING CHANGE: cambia todo"))
        assertEquals(Salto.MAYOR, VersionSemantica.clasificar("fix: algo\n\nBREAKING-CHANGE: cambia todo"))
    }

    @Test
    fun clasificar_sinFormatoNoMueveNada() {
        assertEquals(Salto.NINGUNO, VersionSemantica.clasificar("Merge branch 'main'"))
        assertEquals(Salto.NINGUNO, VersionSemantica.clasificar("   "))
    }

    @Test
    fun saltoDe_mandaElMayor() {
        assertEquals(Salto.MENOR, VersionSemantica.saltoDe(listOf("docs: a", "fix: b", "feat: c", "chore: d")))
        assertEquals(Salto.NINGUNO, VersionSemantica.saltoDe(emptyList()))
    }

    @Test
    fun aplicar_poneACeroLoDeAbajo() {
        assertEquals("2.0.0", VersionSemantica.aplicar("1.4.7", Salto.MAYOR))
        assertEquals("1.5.0", VersionSemantica.aplicar("1.4.7", Salto.MENOR))
        assertEquals("1.4.8", VersionSemantica.aplicar("1.4.7", Salto.PARCHE))
        assertEquals("1.4.7", VersionSemantica.aplicar("1.4.7", Salto.NINGUNO))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aplicar_rechazaNombreMalFormado() {
        VersionSemantica.aplicar("1.4", Salto.PARCHE)
    }

    @Test
    fun siguiente_sinEtiquetaEsElInicial() {
        assertEquals("0.1.0", VersionSemantica.siguiente(null, listOf("feat: x")))
    }

    @Test
    fun siguiente_desdeEtiqueta() {
        assertEquals("0.1.1", VersionSemantica.siguiente("v0.1.0-12", listOf("fix: x", "docs: y")))
        assertEquals("0.2.0", VersionSemantica.siguiente("v0.1.0-12", listOf("fix: x", "feat: y")))
        assertEquals("0.1.0", VersionSemantica.siguiente("v0.1.0-12", listOf("docs: y")))
    }

    @Test
    fun etiqueta_formato() {
        assertEquals("v0.1.0-12", VersionSemantica.etiqueta("0.1.0", 12))
    }

    @Test
    fun mensajesDelLog_separaPorByteNulo() {
        val crudo = "fix: a\n\ncuerpo\n\u0000\nfeat: b\n\u0000\n"
        assertEquals(listOf("fix: a\n\ncuerpo", "feat: b"), VersionSemantica.mensajesDelLog(crudo))
    }
}

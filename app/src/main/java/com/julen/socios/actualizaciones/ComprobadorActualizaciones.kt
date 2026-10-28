package com.julen.socios.actualizaciones

import android.content.Context
import androidx.core.content.edit
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Última versión publicada, la página de su release y, si la release lo incluye, su APK. */
data class VersionPublicada(val version: Version, val url: String, val apk: ApkPublicado? = null)

/**
 * APK adjunto a una release, de [tamano] bytes (siempre más de 0). [sha256] (en hexadecimal) sirve para
 * comprobar que la descarga está entera y sin cambios; GitHub no lo da en las releases antiguas.
 */
data class ApkPublicado(val url: String, val tamano: Long, val sha256: String?)

/** Origen de la última versión publicada. Interfaz para poder sustituirla en tests. */
fun interface FuenteVersiones {
    /** @throws IOException si no se puede consultar. */
    suspend fun ultimaPublicada(): VersionPublicada
}

/** Cuándo se comprobó por última vez, para no consultar GitHub en cada arranque. */
interface RegistroComprobaciones {
    var ultimaComprobacion: Long
}

/**
 * Comprueba si hay una versión de la app más reciente que [versionInstalada]. Las versiones se
 * distribuyen como GitHub Releases, así que es la única forma de enterarse de que hay una nueva.
 */
class ComprobadorActualizaciones(
    private val fuente: FuenteVersiones,
    private val registro: RegistroComprobaciones,
    val versionInstalada: String,
    private val clock: Clock
) {
    data class Resultado(val publicada: VersionPublicada, val hayNueva: Boolean)

    /** null en las builds de desarrollo, que no se pueden comparar con las publicadas. */
    val instalada: Version? = Version.parse(versionInstalada)

    /** Cuándo se consultó GitHub por última vez (0 si nunca). */
    val ultimaComprobacion: Long get() = registro.ultimaComprobacion

    /** Comprobación que pide el usuario. @throws IOException si no se puede consultar. */
    suspend fun comprobar(): Resultado {
        val publicada = fuente.ultimaPublicada()
        registro.ultimaComprobacion = clock.millis()
        return Resultado(publicada, hayNueva = instalada != null && publicada.version > instalada)
    }

    /**
     * Comprobación automática al abrir la app: como mucho una vez al día y en silencio si falla (sin
     * conexión, por ejemplo). Devuelve la versión nueva, o null si no hay o no tocaba comprobar.
     */
    suspend fun comprobarSiToca(): VersionPublicada? {
        if (instalada == null) return null
        if (clock.millis() - registro.ultimaComprobacion in 0 until INTERVALO) return null
        val resultado = try {
            comprobar()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return resultado.publicada.takeIf { resultado.hayNueva }
    }

    companion object {
        val INTERVALO = TimeUnit.DAYS.toMillis(1)
    }
}

/** Última release del repositorio en GitHub, mediante su API pública (no necesita token). */
class GitHubReleases : FuenteVersiones {

    override suspend fun ultimaPublicada(): VersionPublicada = withContext(Dispatchers.IO) {
        conectarGitHub(URL_API, TIMEOUT_MS, accept = "application/vnd.github+json") { conexion ->
            leerRelease(conexion.inputStream.bufferedReader().use { it.readText() })
        }
    }

    companion object {
        private const val URL_API = "https://api.github.com/repos/Julenn06/SOCIOS/releases/latest"
        private const val URL_RELEASES = "https://github.com/Julenn06/SOCIOS/releases/"
        private const val URL_DESCARGAS = URL_RELEASES + "download/"
        private const val TIMEOUT_MS = 10_000

        private val json = Json { ignoreUnknownKeys = true }

        /** Interpreta la respuesta de la API. Separado de la conexión para poder probarlo. */
        internal fun leerRelease(respuesta: String): VersionPublicada {
            val release = try {
                json.decodeFromString(ReleaseDto.serializer(), respuesta)
            } catch (e: IllegalArgumentException) {
                throw IOException("Respuesta de GitHub no reconocida", e)
            }
            val version = Version.parse(release.tagName)
                ?: throw IOException("Etiqueta de versión no reconocida: ${release.tagName}")
            // Solo se abren páginas y se descargan archivos de las releases de este repositorio.
            val url = release.htmlUrl.takeIf { it.startsWith(URL_RELEASES, ignoreCase = true) }
                ?: (URL_RELEASES + "latest")
            val apk = release.assets
                .firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true) && !it.name.endsWith("-debug.apk", ignoreCase = true) &&
                        it.url.startsWith(URL_DESCARGAS, ignoreCase = true) && it.size > 0
                }
                ?.let { ApkPublicado(it.url, it.size, sha256(it.digest)) }
            return VersionPublicada(version, url, apk)
        }

        /** El hash de un "digest" de GitHub ("sha256:3f2a…"), o null si no hay o es de otro tipo. */
        private fun sha256(digest: String?): String? =
            digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.lowercase()
                ?.takeIf { hash -> hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' } }
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<AssetDto> = emptyList()
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
        val digest: String? = null
    )
}

/**
 * Abre [url] y pasa la respuesta a [leer], que debe consumirla antes de volver: después se cierra la conexión.
 * @throws IOException si no se puede conectar o GitHub no responde 200.
 */
internal inline fun <T> conectarGitHub(url: String, timeoutMs: Int, accept: String? = null, leer: (HttpURLConnection) -> T): T {
    val conexion = URL(url).openConnection() as HttpURLConnection
    try {
        conexion.connectTimeout = timeoutMs
        conexion.readTimeout = timeoutMs
        accept?.let { conexion.setRequestProperty("Accept", it) }
        // GitHub rechaza las peticiones sin User-Agent.
        conexion.setRequestProperty("User-Agent", "socios-android")
        if (conexion.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("GitHub respondió ${conexion.responseCode}")
        }
        return leer(conexion)
    } finally {
        conexion.disconnect()
    }
}

class RegistroEnPreferencias(context: Context) : RegistroComprobaciones {
    private val preferencias = context.getSharedPreferences("actualizaciones", Context.MODE_PRIVATE)

    override var ultimaComprobacion: Long
        get() = preferencias.getLong(CLAVE, 0)
        set(valor) = preferencias.edit { putLong(CLAVE, valor) }

    private companion object {
        const val CLAVE = "ultimaComprobacion"
    }
}

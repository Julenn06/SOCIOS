package com.julen.socios.actualizaciones

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bytes descargados de los [total] que tiene el archivo (0 si no se sabe). */
data class Progreso(val descargados: Long, val total: Long) {
    /** De 0 a 1, o null si no se sabe el total. */
    val fraccion: Float? get() = if (total > 0) (descargados.toFloat() / total).coerceIn(0f, 1f) else null
}

/** El archivo descargado no es el publicado: está incompleto o ha cambiado por el camino. */
class DescargaDanadaException(mensaje: String) : IOException(mensaje)

/** Descarga los APK de las versiones nuevas. Interfaz para poder sustituirla en tests. */
interface DescargadorActualizaciones {
    /**
     * Descarga [apk] e informa del progreso (desde otro hilo). Devuelve el archivo descargado.
     * @throws IOException si falla, o [DescargaDanadaException] si el archivo no es el publicado.
     */
    suspend fun descargar(apk: ApkPublicado, version: Version, alProgresar: (Progreso) -> Unit): File

    /** Borra los APK de descargas anteriores, que una vez instalados ya no sirven. */
    suspend fun limpiar()
}

/** Descarga los APK en la caché privada, desde donde se pasan al instalador con el FileProvider. */
class DescargadorGitHub(context: Context) : DescargadorActualizaciones {

    private val directorio = File(context.cacheDir, DIRECTORIO)

    // Para que limpiar() no borre una descarga en curso.
    private val bloqueo = Mutex()

    override suspend fun descargar(apk: ApkPublicado, version: Version, alProgresar: (Progreso) -> Unit): File =
        bloqueo.withLock {
            withContext(Dispatchers.IO) {
                directorio.deleteRecursively()
                directorio.mkdirs()
                val destino = File(directorio, "socios-$version.apk")
                val parcial = File(directorio, "${destino.name}.parcial")
                try {
                    val conexion = URL(apk.url).openConnection() as HttpURLConnection
                    try {
                        conexion.connectTimeout = TIMEOUT_MS
                        conexion.readTimeout = TIMEOUT_MS
                        conexion.setRequestProperty("User-Agent", "socios-android")
                        // GitHub redirige a su almacenamiento de archivos; HttpURLConnection lo sigue solo.
                        if (conexion.responseCode != HttpURLConnection.HTTP_OK) {
                            throw IOException("GitHub respondió ${conexion.responseCode}")
                        }
                        conexion.inputStream.use { entrada ->
                            parcial.outputStream().use { salida -> copiarVerificando(entrada, salida, apk, alProgresar) }
                        }
                    } finally {
                        conexion.disconnect()
                    }
                    if (!parcial.renameTo(destino)) throw IOException("No se ha podido guardar la descarga")
                    destino
                } catch (e: Throwable) {
                    parcial.delete()
                    throw e
                }
            }
        }

    override suspend fun limpiar() {
        bloqueo.withLock { withContext(Dispatchers.IO) { directorio.deleteRecursively() } }
    }

    companion object {
        /** Debe coincidir con res/xml/file_paths.xml. */
        private const val DIRECTORIO = "actualizaciones"
        private const val TIMEOUT_MS = 15_000
        private const val TAMANO_BUFFER = 64 * 1024

        /**
         * Copia la descarga en [salida] comprobando que coincide en tamaño y en SHA-256 con lo publicado.
         * Separado de la conexión para poder probarlo.
         */
        internal suspend fun copiarVerificando(
            entrada: InputStream,
            salida: OutputStream,
            apk: ApkPublicado,
            alProgresar: (Progreso) -> Unit
        ) {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(TAMANO_BUFFER)
            var descargados = 0L
            alProgresar(Progreso(0, apk.tamano))
            while (true) {
                currentCoroutineContext().ensureActive()
                val leidos = entrada.read(buffer)
                if (leidos < 0) break
                descargados += leidos
                if (apk.tamano > 0 && descargados > apk.tamano) {
                    throw DescargaDanadaException("La descarga ocupa más de ${apk.tamano} bytes")
                }
                salida.write(buffer, 0, leidos)
                sha256.update(buffer, 0, leidos)
                alProgresar(Progreso(descargados, apk.tamano))
            }
            if (apk.tamano > 0 && descargados != apk.tamano) {
                throw DescargaDanadaException("Descarga incompleta: $descargados de ${apk.tamano} bytes")
            }
            val hash = sha256.digest().joinToString("") { "%02x".format(it) }
            if (apk.sha256 != null && hash != apk.sha256) {
                throw DescargaDanadaException("El SHA-256 de la descarga no coincide con el publicado")
            }
        }
    }
}

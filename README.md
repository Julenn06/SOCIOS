# SOCIOS

App Android para registrar los socios conseguidos cada semana y calcular la ganancia: base (cuota × 2),
bonus por tramos o ajustado a mano, retención de IRPF y neto. Incluye histórico global y exportación a
PDF, CSV y JSON (copia de seguridad que se puede volver a importar).

## Estructura

| Paquete | Contenido |
|---|---|
| `model` | Modelo de dominio (`Socio`, `DiaSemana`, `BonoRegaloInfo`, `IrpfVigencia`, importes predefinidos). |
| `data` | Repositorios, Room (`data/local`, con migraciones) y formato de copias de seguridad (`data/backup`). |
| `export` | Generación del PDF y lectura/escritura de archivos (Storage Access Framework y FileProvider). |
| `util` | Cálculos (`CalculoComisiones`), semanas ISO (`DateUtils`), formato de importes y animaciones. |
| raíz | `SociosApp` (dependencias), `MainActivity`, `MainViewModel`, bottom sheets y adapters. |

Las semanas se identifican con su clave ISO-8601 (`2026-W01` va del 29-dic-2025 al 4-ene-2026).

## Desarrollo

```bash
./gradlew test                    # tests unitarios (cálculos, fechas, CSV/JSON, ViewModel)
./gradlew connectedAndroidTest    # tests instrumentados (migraciones de Room y DAO); necesita un dispositivo
```

> `connectedAndroidTest` desinstala la app del dispositivo al terminar, **borrando sus datos**. No lo
> lances contra un móvil con datos reales.

Los esquemas de Room se exportan a `app/schemas/`. Al cambiar una entidad hay que subir la versión de
`AppDatabase`, añadir la `Migration` correspondiente y un test en `MigracionesTest`.

## Publicar una versión

1. Crea y sube un tag: `git tag v1.1.0 && git push origin v1.1.0`.
2. El workflow `Build and Release` pasa los tests, firma el APK y crea la GitHub Release `v1.1.0`.

Los push a `main` solo generan el APK como artefacto del workflow, sin publicar release. El
`versionCode` es el número de ejecución del workflow, así que cada APK es una actualización del anterior.

### Clave de firma (configuración única)

Todos los APK deben firmarse con la **misma** clave; si cambia, Android obliga a desinstalar la app y se
pierden los datos. Crea la clave una vez y guárdala en un lugar seguro (fuera del repositorio):

```powershell
keytool -genkeypair -v -keystore socios-release.jks -alias socios -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("socios-release.jks")) | Set-Content -NoNewline socios-release.jks.b64
```

Y añade estos secrets al repositorio (Settings → Secrets and variables → Actions, o con `gh secret set`):

| Secret | Valor |
|---|---|
| `SOCIOS_KEYSTORE_BASE64` | Contenido de `socios-release.jks.b64` |
| `SOCIOS_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `SOCIOS_KEY_ALIAS` | `socios` (o el alias que usaras) |
| `SOCIOS_KEY_PASSWORD` | Contraseña de la clave |

Para compilar una release firmada en local, define las mismas variables de entorno (con
`SOCIOS_KEYSTORE_PATH` apuntando al `.jks`) y ejecuta `./gradlew assembleRelease`.

**Al pasar a la clave de release por primera vez**, el APK no podrá instalarse encima del anterior
(firmado con una clave de debug distinta): exporta antes una copia JSON desde la app, desinstala,
instala el nuevo APK e importa la copia.

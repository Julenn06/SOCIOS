# SOCIOS · PWA

La misma app que la de Android (`app/`), como página web instalable: misma pantalla, mismos paneles, mismos
cálculos (céntimos enteros, IRPF 21,48 %, tramos del bonus), mismas animaciones y el mismo vidrio líquido. Funciona
sin conexión y guarda los datos en el navegador (IndexedDB).

Sin compilación ni dependencias: HTML, CSS y módulos JavaScript tal cual. Solo `vendor/jspdf.umd.min.js` (jsPDF 2.5.2,
licencia MIT) para el informe PDF, que se carga al exportar.

## Probarla

Cualquier servidor estático vale; el service worker y el bloqueo con huella necesitan `https` o `localhost`:

```bash
npx serve pwa          # o: python -m http.server -d pwa 8000
```

## Pasar los datos desde la app Android

Las copias JSON y CSV tienen el mismo formato (versión 5) en las dos: en la app, **Más → Copias y exportación →
Copia de seguridad JSON**, y en la PWA, **Más → Copias y exportación → Importar → Copia JSON**. Y al revés.

## Publicar una versión

```bash
node pwa/herramientas/version.mjs 1.1.0 "Novedad uno" "Novedad dos"
```

Cambia el número en `js/version.js`, `sw.js` y `version.json`, calcula lo que ocupa la descarga y comprueba que el
service worker guarda todos los archivos (si se añade uno nuevo, hay que ponerlo en la lista `ARCHIVOS` de `sw.js`;
`--comprobar` solo revisa). Al abrirse, la app mira `version.json` como mucho una vez al día y avisa; "Actualizar"
descarga la versión nueva con la animación de la app y se recarga con ella.

Está publicada en **https://socios.jfl06studio.com** (un sitio propio en Hostinger, aparte de jfl06studio.com) y se
publica sola: cada push a `main` que toca `pwa/` lanza el workflow `Publicar la web` (`.github/workflows/web.yml`), que
copia solo lo público (`index.html`, `manifest.webmanifest`, `sw.js`, `version.json`, `.htaccess`, `css/`, `js/`,
`fonts/`, `img/`, `icons/`, `vendor/`) a la rama `web`, y Hostinger despliega esa rama en cuanto cambia. El `.htaccess`
fuerza HTTPS y marca `sw.js`, `version.json` y el código como `no-cache`.

Así que publicar es: subir la versión con el comando de arriba, hacer commit y push a `main`.

`herramientas/iconos.mjs` vuelve a generar `js/iconos.js` desde los iconos de la app Android.

## Estructura

| Archivo | Equivale a |
|---|---|
| `js/calculos.js`, `js/fechas.js`, `js/formato.js` | `util/CalculoComisiones`, `Centimos`, `EscalaGrafico`, `DateUtils`, `NumberExtensions` |
| `js/estado.js`, `js/db.js` | `MainViewModel`, repositorios y Room |
| `js/copias.js`, `js/pdf.js` | `data/backup` y `PdfReportGenerator` |
| `js/pantalla.js`, `js/filas.js` | `MainActivity`, `CabeceraResumen`, `SelectorSemana`, `FiltroDias`, `ListaSemana`, `SocioAdapter` |
| `js/hojas/*` | Los paneles inferiores (`*BottomSheetDialog`) |
| `js/vistas/*` | Las vistas dibujadas a mano (barra del bonus, franja de días, gráfico, miniaturas del tema, descarga) |
| `js/avisos.js`, `js/paneles.js`, `js/movimiento.js` | `Avisos`, `PanelInferior` y diálogos, `Movimiento` y `NumberAnimators` |
| `js/ajustes.js`, `js/pantalla-bloqueo.js` | Tema, `Bloqueo` (con WebAuthn) y actualizaciones (service worker) |

## Diferencias inevitables con la app

- **Widget de la pantalla de inicio:** la web no puede tenerlos. El atajo "Nuevo socio" sí está (mantener pulsado el
  icono de la app instalada).
- **Bloqueo:** pide la huella, la cara o el PIN del dispositivo con WebAuthn (una llave guardada en el propio
  dispositivo). Como en la app, es una pantalla que tapa los datos, no un cifrado.
- **Actualizar:** en lugar de descargar e instalar un APK, el service worker descarga la versión nueva y la página se
  recarga con ella. Tus socios y ajustes se conservan.
- **Exportar → Guardar:** donde el navegador no deja elegir carpeta, el archivo va a Descargas.
- La vibración solo existe en los navegadores que la permiten (Chrome en Android sí; Safari no).

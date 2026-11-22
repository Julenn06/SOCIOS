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

## Tests

```bash
node --test "pwa/test/*.test.mjs"
```

Sin dependencias (el `node:test` de Node 22 o más). Comprueban las cuentas (céntimos, IRPF, tramos del bonus, neto
cobrado), las semanas ISO, los textos de importes y fechas, que las copias JSON y CSV tienen el formato de la app
Android y se vuelven a leer igual, con los mismos casos que sus tests unitarios, y cómo se juntan los cambios de dos
dispositivos campo a campo. El workflow que publica la web los pasa antes: si alguno falla, no se publica nada.

**Recorridos en Chrome** (`test/e2e/`): la web de verdad en un Chrome sin pantalla, con las cabeceras del `.htaccess`,
contra el servidor de la cuenta en memoria. Se registra, guarda socios, abre todos los paneles, saca el PDF, sincroniza
dos dispositivos que cambian el mismo socio sin conexión, crea una llave de acceso y entra con ella, se abre sin
conexión y comprueba que los errores llegan al servidor sin datos. Falla si hay un error de JavaScript, si la CSP o
Trusted Types bloquean algo y si un control no tiene nombre accesible o mide menos de 24×24 px. Lo pasan los workflows
de la web y del servidor antes de publicar.

```bash
npm ci --prefix servidor
node --test "pwa/test/e2e/*.test.mjs"     # Chrome en la ruta de siempre o en la variable CHROME
```

## Cuenta y sincronización (solo la web)

Al abrir la web sale **Iniciar sesión** o **Registrarse**. Registrarse pide primero la contraseña de registro y, si
es correcta, "¿Cómo te llamas?" y una contraseña; para entrar, el nombre vale escrito de cualquier forma. Con la sesión
iniciada, los datos se guardan también en la cuenta (servidor en `servidor/`, https://socios-api.jfl06studio.com) y son
los mismos en todos los dispositivos donde se abra la web.

La web sigue funcionando sin conexión: cada cambio queda anotado como pendiente en IndexedDB (almacén `pendientes`, en
la misma transacción que el dato) y se sube al volver la red. Se sincroniza al abrirla, al poco de cada cambio, al
volver a ella y cada 30 s mientras se ve. Si el mismo registro cambia en dos sitios, se juntan campo a campo (cada
pendiente anota la hora de cada campo que cambia, `db.camposCambiados` y `db.juntar`): de cada campo gana el cambio
más reciente, así que cambiar lo cobrado en un móvil y el nombre en otro conserva las dos cosas. Con los datos viajan
el tema, la última cuota y los pendientes ocultos; el bloqueo con huella es de cada dispositivo. "Más → Cuenta" dice
cuándo se sincronizó y permite cerrar sesión (los datos se quitan del dispositivo y siguen en la cuenta). En
"Más → Cuenta → Nombre y contraseña" (`js/hojas/cuenta.js`) se cambian el nombre y la contraseña; si no se recuerda la
actual, se restablece con la contraseña de registro. Al cambiarla, los demás dispositivos tienen que volver a entrar.

**Llaves de acceso** (passkeys): en "Más → Cuenta → Seguridad" se crea una en el dispositivo (o en el móvil, con el QR
que enseña el navegador) y desde entonces se entra con "Entrar con llave de acceso", con la huella, la cara o el PIN.
Ahí mismo están las sesiones abiertas y la actividad de la cuenta.

**Errores** (`js/errores.js`, lo primero que carga `main.js`): los fallos de JavaScript, las promesas sin atender, los
archivos que no cargan y lo que bloquea la CSP se mandan al servidor sin datos de la persona; la CSP lleva además
`report-uri` para cuando la app ni siquiera arranca. La vigilancia (`.github/workflows/vigilancia.yml`, cada 30 min)
comprueba que la web y el servidor respondan con sus cabeceras y abre un issue si algo cae o hay errores nuevos.

**La app Android no usa la cuenta**: para pasar sus datos a la web, las copias (abajo). Al entrar en la cuenta, lo que
ya hubiera en ese navegador sube a ella.

## Pasar los datos desde la app Android

Las copias JSON y CSV tienen el mismo formato (versión 5) en las dos: en la app, **Más → Copias y exportación →
Copia de seguridad JSON**, y en la PWA, **Más → Copias y exportación → Importar → Copia JSON**. Y al revés.

## Publicar una versión

```bash
node pwa/herramientas/version.mjs 1.1.0 "Novedad uno" "Novedad dos"
```

Cambia el número en `js/version.js`, `sw.js` y `version.json`, calcula lo que ocupa la descarga y comprueba que el
service worker guarda todos los archivos (si se añade uno nuevo, hay que ponerlo en la lista `ARCHIVOS` de `sw.js`;
`--comprobar` solo revisa). Al abrir la app y al volver a ella (como mucho cada 30 minutos), mira `version.json`;
si hay una versión nueva, sale una pantalla entera con sus novedades (`js/pantalla-novedad.js`): "Actualizar ahora"
descarga la versión nueva con la animación de la app y se recarga con ella, y "Más tarde" la quita hasta la próxima
vez que se abra la app (para entonces el service worker ya suele haberla puesto sola). Si hay un panel abierto o la
app está bloqueada, espera a que se cierre.

Está publicada en **https://socios.jfl06studio.com** (un sitio propio en Hostinger, aparte de jfl06studio.com) y se
publica sola: cada push a `main` que toca `pwa/` lanza el workflow `Publicar la web` (`.github/workflows/web.yml`), que
copia solo lo público (`index.html`, `manifest.webmanifest`, `sw.js`, `version.json`, `.htaccess`, `css/`, `js/`,
`fonts/`, `img/`, `icons/`, `vendor/`) a la rama `web`, y Hostinger despliega esa rama en cuanto cambia.

El `.htaccess` fuerza HTTPS, marca `sw.js`, `version.json` y el código como `no-cache` (al actualizar, el service
worker solo descarga lo que ha cambiado) y pone las cabeceras de seguridad: una `Content-Security-Policy` que solo deja
ejecutar código del propio sitio, más `Permissions-Policy`, `Cross-Origin-Opener-Policy` y HSTS. El script del tema de
`index.html` va en línea y la política lo permite por su hash: si se cambia ese script, `version.mjs --comprobar` (que
también corre el workflow) dice qué hash poner en el `.htaccess`.

Dos reglas que impone esa política (desde la 1.0.16):

- **Nada de `style=""` en el HTML** (`style-src 'self'`, sin `'unsafe-inline'`): lo puntual va en las clases de
  utilidad de `css/app.css` ("Utilidades") y lo calculado, con `el.style` desde el código.
- **Trusted Types** (`require-trusted-types-for 'script'`): no se puede asignar una cadena a `innerHTML` ni registrar un
  script por URL sin pasar por la política `socios` de `js/confianza.js`. Para crear HTML, `html()` de
  `componentes.js` o `htmlConfiable()`; lo que escribe el usuario, siempre escapado con `esc()`.

La sesión de la cuenta no se guarda en el navegador: va en una cookie `HttpOnly` del servidor que el código no puede leer
(ver `servidor/README.md`).

La interfaz usa las fuentes Geist en WOFF2; las TTF solo las usa el informe PDF (jsPDF no lee WOFF2).

Así que publicar es: subir la versión con el comando de arriba, hacer commit y push a `main`.

`herramientas/iconos.mjs` vuelve a generar `js/iconos.js` desde los iconos de la app Android.

## Rendimiento

Medido en el emulador (Chrome 149) con los 533 socios reales, contando fotogramas y con perfiles del navegador. Lo que
importa para que todo vaya fluido:

- **Nada de leer medidas del diseño en una animación** (`clientWidth`, `scrollWidth`, `getBoundingClientRect`): con
  algo cambiando en la misma pantalla, obligan a recalcular el diseño en cada fotograma. Los importes que se encogen
  para caber (`autoajustar` en `componentes.js`) miden el texto con `measureText` y el ancho con un ResizeObserver; los
  lienzos usan `medida()` de `lienzo.js`, **redondeada** como `clientWidth` (con decimales el lienzo no casa con los
  píxeles de la pantalla y la animación pierde fotogramas).
- **El fondo de los paneles aparece por opacidad**, ya desenfocado: animar el radio del desenfoque obliga a recalcularlo
  sobre toda la pantalla en cada fotograma.
- **Listas largas por tandas**: la búsqueda pinta primero 24 filas y luego 30 por fotograma; solo las primeras 20
  animan su entrada. `content-visibility: auto` se probó y se quitó: con las 70 semanas del histórico, el trabajo pasaba
  a hacerse al desplazar y lo volvía a trompicones.
- **Lo del PDF, bajo demanda** (`BAJO_DEMANDA` en `sw.js`): el generador y las fuentes TTF (unos 700 KB, casi la mitad)
  no van en la instalación. Se guardan aparte (caché `pdf-socios`) al sacar el primer PDF o, con conexión y sin ahorro
  de datos, un minuto después de abrir la app; desde entonces el PDF funciona sin conexión.
- Los iconos se interpretan una vez y se clonan; los paneles cerrados se quitan del documento cuando el navegador está
  libre; lo que el servidor devuelve igual que se subió no relee ni repinta la pantalla.

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
| `js/pantalla-novedad.js` | Solo en la web: la pantalla de "Hay una actualización" |
| `js/instalacion.js` | Solo en la web: el botón "Instalar la app" de "Más" |
| `js/nube.js`, `js/pantalla-cuenta.js` | Solo en la web: la cuenta, la sincronización y la pantalla de iniciar sesión o registrarse |
| `test/` | Los tests de la lógica (`node --test "pwa/test/*.test.mjs"`) |

## Diferencias inevitables con la app

- **Widget de la pantalla de inicio:** la web no puede tenerlos. El atajo "Nuevo socio" sí está (mantener pulsado el
  icono de la app instalada).
- **Bloqueo:** pide la huella, la cara o el PIN del dispositivo con WebAuthn (una llave guardada en el propio
  dispositivo). Como en la app, es una pantalla que tapa los datos, no un cifrado.
- **Actualizar:** en lugar de descargar e instalar un APK, el service worker descarga la versión nueva y la página se
  recarga con ella. Tus socios y ajustes se conservan.
- **Exportar → Guardar:** donde el navegador no deja elegir carpeta, el archivo va a Descargas.
- **Instalar la app:** solo existe en la web. "Más" lo ofrece cuando el navegador puede instalarla (Chrome, Edge) y aún
  no está instalada; la confirmación es siempre el cuadro del propio navegador.
- **Datos guardados:** al abrirse, la web pide al navegador que no borre sus datos si le falta espacio
  (`navigator.storage.persist()` en `db.js`); Chrome lo concede sin preguntar si está instalada o se usa a menudo.
- **Compartir una copia con la app:** instalada en Android, "Compartir → Gestión de Socios" desde WhatsApp, Archivos o
  Drive abre directamente la revisión de "Importar" (`share_target` del manifest; el service worker recibe el archivo en
  `/compartir` y lo deja en la caché `compartido-socios` hasta que la app lo recoge).
- **Varias ventanas:** si está abierta a la vez instalada y en una pestaña (o en dos pestañas), lo que se guarda en una
  aparece en las demás al momento.
- La vibración solo existe en los navegadores que la permiten (Chrome en Android sí; Safari no).

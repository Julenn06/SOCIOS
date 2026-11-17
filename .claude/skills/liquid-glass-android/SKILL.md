---
name: liquid-glass-android
description: Vidrio líquido (estilo Liquid Glass de iOS 26) en esta app Android de vistas XML, con el paquete com.julen.socios.vidrio. Usar al añadir, cambiar o revisar cualquier superficie translúcida, barra, botón flotante, aviso, panel o diálogo con vidrio, desenfoque o refracción.
---

# Vidrio líquido en socios (Android, vistas XML)

Adaptación a Android de los criterios de Liquid Glass (Apple, iOS 26; resumidos en la skill
`affaan-m/ecc@liquid-glass-design`, que es solo para SwiftUI/UIKit) sobre APIs de Android verificadas en
developer.android.com: `RenderNode` + `RenderEffect` (API 31), `RuntimeShader` AGSL (API 33) y el desenfoque entre
ventanas (`FLAG_BLUR_BEHIND`, API 31).

## Dónde va el vidrio (y dónde no)

- **Sí**: la capa de navegación y controles que flota sobre el contenido: barra superior, botón flotante, avisos,
  paneles inferiores y diálogos.
- **No**: el contenido. Las tarjetas, placas de color y la cabecera verde siguen como están (ver memoria
  "Estilo libro de cuentas": el diseño de tarjetas es el elegido).
- Nunca vidrio dentro de vidrio: lo que va encima de un vidrio es un velo translúcido con filo (como el selector de
  semana sobre la barra), no otro vidrio.
- Brillo al pulsar (`interactivo`) solo en lo que se toca.

## Cómo está hecho (paquete `vidrio`)

| Pieza | Qué hace |
|---|---|
| `FuenteVidrio` | FrameLayout que guarda lo que pintan sus hijos en un `RenderNode` (API 31+). Los vidrios lo vuelven a pintar sin copiar píxeles. Cuando algo de dentro se invalida, invalida a sus vidrios antes de pintar (`onDescendantInvalidated`): se regraban en el mismo fotograma, sin uno de más. |
| `ScrollBajoVidrio` | El scroll de la pantalla principal: pasa por debajo de la barra (relleno de arriba = alto de la barra) y del botón (relleno de abajo); al llevar una vista a la pantalla (TalkBack, teclado, `scrollTo()`) la deja entre los dos. La barra se queda con los toques que no recogen sus botones (`setOnTouchListener` en `ponerVidrio`), para que no atraviesen al contenido. |
| `VidrioDrawable` | El vidrio: API 33+ un shader AGSL (refracción en el borde, dispersión, tinte, filo de luz arriba a la izquierda, brillo al pulsar) sobre lo desenfocado; API 31–32 solo desenfoque con tinte y filo pintados. |
| `EstiloVidrio` | Tinte, fuerza del tinte, color opaco de siempre, radio, desenfoque y refracción. Presets `BARRA`, `BOTON`, `AVISO`. |
| `Vidrio.aplicar(vista, estilo, desde)` | Pone el vidrio de fondo. Devuelve false y no toca nada si no se puede. |
| `VidrioVentana` | Paneles: desenfoque de lo de detrás y panel translúcido mientras el sistema lo permita (escucha `addCrossWindowBlurEnabledListener`; en ahorro de batería vuelve a opaco y oscurecido). |
| `values-v31/themes.xml` | Diálogos: `windowBlurBehindEnabled` (siguen opacos). |

## Reglas al añadir un vidrio

1. La vista tiene que pintarse **después** de la `FuenteVidrio` (hermana posterior en el layout o en otra capa de la
   misma ventana) y **nunca dentro** (`Vidrio.aplicar` lo rechaza).
2. Otra ventana (panel, diálogo) no ve la fuente: ahí se usa `VidrioVentana`, no `VidrioDrawable`.
3. Sin vidrio (Android 11 o anterior, o con más contraste en Android 14+, `Vidrio.reducirTransparencia`) la vista se
   queda exactamente como era: no hace falta un segundo diseño.
4. Contraste: el texto sobre vidrio tiene que llegar a 4,5:1 con el peor fondo posible detrás (por eso
   `vidrio_boton` es más oscuro que `primary` y la barra tiñe al 62 %). Probar en claro y en oscuro.
5. Lint `NewApi` no deduce el nivel de API de un null: usar comprobaciones explícitas de `Build.VERSION.SDK_INT`.
6. El vidrio interactivo sustituye al ripple de Material: tiene que marcar también el foco (teclado, mando,
   acceso por interruptores) y el hover, no solo la pulsación.
7. Con contraste alto activado con la app abierta, `Vidrio.recrearAlCambiarContraste` rehace la pantalla.
8. Verificar en un emulador con API 33+ (el shader AGSL solo se compila al ejecutarse) y pasar los tests de
   interfaz con la pantalla de la CI (320×640, 160 dpi, sin animaciones).

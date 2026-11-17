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
| `VidrioDrawable` | El vidrio. API 33+: un shader AGSL sobre lo desenfocado con lente de perfil redondo en el borde (se hunde al pulsar), dispersión, vibrancia (saturación), **tinte adaptativo** (mínimo `fuerzaTinte`, y por bisección lo justo para que el texto más exigente de `textos` llegue a 4,5:1 con la luminancia WCAG real), grosor (luz por dentro arriba, sombra abajo), brillo superior, filo de luz y brillo al pulsar o con foco. API 31–32: desenfoque con un tinte fijo (`fuerzaSinShader`) y el filo pintados. Muestrea lo de detrás a escala real aunque el botón esté hundido. |
| `EstiloVidrio` | Tinte, fuerzas, textos que lleva encima, color opaco de siempre, radio, desenfoque, refracción, banda, saturación y brillo superior. Presets `BARRA` (solo el título: muy transparente), `CAPSULA` (el selector de semana, que flota sobre la barra con sus propios textos), `BOTON` y `AVISO`. |
| `Vidrio.aplicar(vista, estilo, desde)` | Pone el vidrio de fondo y devuelve un `VidrioPuesto` (null y sin tocar nada si no se puede). `mostrarBorde(f)` enciende el borde de la barra según el contenido que pasa por debajo. |
| `DebajoDeLaBarra` | Behavior de la fuente: pone el relleno de arriba del scroll al medir (la barra se mide antes), sin un segundo pase de layout. |
| `VidrioVentana` | Paneles y diálogos: desenfoque de lo de detrás (y, en los diálogos, bajo su propio fondo), superficie translúcida y filo de luz mientras el sistema lo permita (escucha `addCrossWindowBlurEnabledListener`; en ahorro de batería vuelven a opacos y oscurecidos). Los diálogos se muestran con `MaterialAlertDialogBuilder.mostrarConVidrio()`. |
| `values-v31/themes.xml` | Diálogos: `windowBlurBehindEnabled` y ventana translúcida. |

## Reglas al añadir un vidrio

1. La vista tiene que pintarse **después** de la `FuenteVidrio` (hermana posterior en el layout o en otra capa de la
   misma ventana) y **nunca dentro** (`Vidrio.aplicar` lo rechaza).
2. Otra ventana (panel, diálogo) no ve la fuente: ahí se usa `VidrioVentana`, no `VidrioDrawable`.
3. Sin vidrio (Android 11 o anterior, o con más contraste en Android 14+, `Vidrio.reducirTransparencia`) la vista se
   queda exactamente como era: no hace falta un segundo diseño.
4. Contraste: con el shader lo garantiza el tinte adaptativo, siempre que `textos` incluya todos los colores de
   texto que van encima (también los que cambian según el estado). Sin shader (Android 12) lo garantiza
   `fuerzaSinShader`, que tiene que bastar con el peor fondo posible. Probar en claro y en oscuro.
   Si un vidrio lleva un texto mucho menos contrastado que el resto, mejor darle su propia cápsula (como el
   selector de semana) que obligar a toda la superficie a ser casi opaca.
5. Lint `NewApi` no deduce el nivel de API de un null: usar comprobaciones explícitas de `Build.VERSION.SDK_INT`.
6. El vidrio interactivo sustituye al ripple de Material: tiene que marcar también el foco (teclado, mando,
   acceso por interruptores) y el hover, no solo la pulsación.
7. Con contraste alto activado con la app abierta, `Vidrio.recrearAlCambiarContraste` rehace la pantalla.
8. Verificar en un emulador con API 33+ (el shader AGSL solo se compila al ejecutarse) y pasar los tests de
   interfaz con la pantalla de la CI (320×640, 160 dpi, sin animaciones).

// Piezas comunes de los paneles: campos (TextInputLayout), filas con placa (item_formato), opciones (cuotas, días),
// tarjetas elegibles e interruptores en tarjeta.
import { icono } from './iconos.js';
import { ICONOS_WEB } from './iconos-web.js';
import { esc, eurosCompacto, parseImporte, importeInput } from './formato.js';
import { desplegar, haptica, muellePulsar, pulsar } from './movimiento.js';
import { IMPORTE_MAXIMO, redondearCentimos } from './calculos.js';

/**
 * Rellena los <span data-icono="..."> de [raiz] con su SVG. Cada icono se interpreta una sola vez y después se clona:
 * interpretar el texto del SVG en cada panel que se abre costaba más que copiar el ya hecho.
 */
const iconosHechos = new Map();
export function pintarIconos(raiz) {
  raiz.querySelectorAll('[data-icono]').forEach((el) => {
    const nombre = el.dataset.icono;
    let molde = iconosHechos.get(nombre);
    if (!molde) {
      molde = document.createElement('template');
      molde.innerHTML = icono(nombre);
      iconosHechos.set(nombre, molde);
    }
    el.replaceChildren(molde.content.cloneNode(true));
    el.removeAttribute('data-icono');
  });
}

/** Crea un elemento desde HTML. */
export function html(texto) {
  const t = document.createElement('template');
  t.innerHTML = texto.trim();
  const el = t.content.firstElementChild;
  pintarIconos(el);
  return el;
}

/**
 * Un campo con borde, icono y etiqueta que flota al escribir. [tipo]: 'decimal', 'texto', 'area' o 'buscar'.
 * Devuelve { el, input, ayuda, valor(), poner(v), error(t), foco() }.
 */
export function crearCampo({ etiqueta, iconoCampo, tipo = 'texto', maxLength = null, capitalizar = 'sentences', ayuda = false, limpiar = false, grande = false, autofocus = false, autocompletar = null, nombre = null, requerido = false }) {
  // Con [nombre], el campo tiene un id y un name fijos: los gestores de contraseñas los necesitan para guardar y
  // rellenar (con uno al azar en cada apertura no lo reconocerían).
  const id = nombre ?? `c${Math.random().toString(36).slice(2, 9)}`;
  const atributos = `${nombre ? ` name="${nombre}"` : ''}${requerido ? ' required' : ''}`;
  // La búsqueda no lleva corrector ni mayúsculas: se escriben trozos de nombres o una cuota, no frases.
  const extras = tipo === 'buscar'
    ? ' enterkeyhint="search" autocorrect="off" spellcheck="false"'
    : tipo === 'decimal' ? ' enterkeyhint="done"' : '';
  // Contraseñas: con su autocompletado, para que el gestor de contraseñas del móvil las guarde y las rellene.
  const control = tipo === 'contrasena'
    ? `<input id="${id}"${atributos} type="password" autocomplete="${autocompletar ?? 'current-password'}" autocapitalize="none" autocorrect="off" spellcheck="false" aria-describedby="${id}-ayuda"${maxLength ? ` maxlength="${maxLength}"` : ''}${autofocus ? ' autofocus' : ''}>`
    : tipo === 'area'
    ? `<textarea id="${id}" rows="1" autocapitalize="${capitalizar}" aria-describedby="${id}-ayuda"${maxLength ? ` maxlength="${maxLength}"` : ''}></textarea>`
    : `<input id="${id}"${atributos} type="${tipo === 'buscar' ? 'search' : 'text'}" ${tipo === 'decimal' ? 'inputmode="decimal" autocomplete="off"' : `autocapitalize="${tipo === 'buscar' ? 'none' : capitalizar}" autocomplete="${autocompletar ?? 'off'}"`} aria-describedby="${id}-ayuda"${maxLength ? ` maxlength="${maxLength}"` : ''}${extras}${autofocus ? ' autofocus' : ''}>`;
  const caja0 = `
      <div class="campo${grande ? ' grande' : ''}">
        <span class="campo-icono icono" data-icono="${iconoCampo}"></span>
        ${control}
        <label for="${id}">${esc(etiqueta)}</label>
        <fieldset class="campo-borde" aria-hidden="true"><legend><span>${esc(etiqueta)}</span></legend></fieldset>
        ${limpiar ? `<button class="limpiar oculto" type="button" aria-label="Borrar el texto"><span class="icono" data-icono="close"></span></button>` : ''}
        ${tipo === 'contrasena' ? `<button class="ver-contrasena" type="button" aria-label="Mostrar la contraseña" aria-pressed="false" aria-controls="${id}"><span class="icono">${ICONOS_WEB.ojo}</span></button>` : ''}
      </div>`;
  const envoltura = html(`
    <div>
      ${tipo === 'buscar' ? `<search>${caja0}</search>` : caja0}
      <div class="ayuda-campo${ayuda ? '' : ' oculto'}" id="${id}-ayuda" aria-live="polite"></div>
    </div>`);
  const caja = envoltura.querySelector('.campo');
  const input = envoltura.querySelector('input, textarea');
  const textoAyuda = envoltura.querySelector('.ayuda-campo');
  const botonLimpiar = envoltura.querySelector('.limpiar');
  let ayudaNormal = '';
  // Las notas crecen hasta tres líneas con field-sizing (CSS); solo donde no existe se calcula a mano.
  const crecerAMano = tipo === 'area' && !CSS.supports('field-sizing', 'content');
  const actualizar = () => {
    caja.classList.toggle('lleno', input.value !== '');
    botonLimpiar?.classList.toggle('oculto', input.value === '');
    if (crecerAMano) {
      input.style.height = 'auto';
      input.style.height = `${Math.min(input.scrollHeight, 3 * 21.6 + 32)}px`;
    }
  };
  input.addEventListener('input', actualizar);
  // El ojo de las contraseñas: muestra lo escrito para comprobarlo y lo vuelve a ocultar. El cursor se queda donde estaba.
  const botonVer = envoltura.querySelector('.ver-contrasena');
  const verContrasena = (ver) => {
    if (!botonVer) return;
    const [inicio, fin] = [input.selectionStart, input.selectionEnd];
    input.type = ver ? 'text' : 'password';
    botonVer.setAttribute('aria-pressed', String(ver));
    botonVer.querySelector('.icono').innerHTML = ver ? ICONOS_WEB.ojoTachado : ICONOS_WEB.ojo;
    if (document.activeElement === input) input.setSelectionRange(inicio, fin);
  };
  botonVer?.addEventListener('pointerdown', (e) => e.preventDefault());
  botonVer?.addEventListener('click', () => {
    haptica.click();
    verContrasena(input.type === 'password');
    input.focus();
  });
  botonLimpiar?.addEventListener('click', () => {
    input.value = '';
    input.dispatchEvent(new Event('input'));
    input.focus();
  });
  const campo = {
    el: envoltura,
    input,
    valor: () => input.value,
    poner(v) {
      input.value = v ?? '';
      actualizar();
    },
    error(t) {
      caja.classList.toggle('error', !!t);
      if (t) input.setAttribute('aria-invalid', 'true');
      else input.removeAttribute('aria-invalid');
      textoAyuda.classList.toggle('error', !!t);
      textoAyuda.textContent = t || ayudaNormal;
      textoAyuda.classList.toggle('oculto', !t && !ayudaNormal);
    },
    ayuda(t) {
      ayudaNormal = t;
      if (!caja.classList.contains('error')) {
        textoAyuda.textContent = t;
        textoAyuda.classList.toggle('oculto', !t);
      }
    },
    /** Vuelve a ocultar la contraseña (al enviar: así el navegador la reconoce para guardarla). */
    ocultar() {
      verContrasena(false);
    },
    foco() {
      input.focus({ preventScroll: false });
    }
  };
  requestAnimationFrame(actualizar);
  return campo;
}

/** Fila con placa de color, título, descripción y flecha (item_formato). */
export function filaFormato({ iconoPlaca, tono, titulo, detalle, alPulsar, tituloRojo = false, flecha = true }) {
  const el = html(`
    <button class="tarjeta tocable fila-formato" type="button">
      <span class="placa tono-${tono}"><span class="icono" data-icono="${iconoPlaca}"></span></span>
      <span class="columna-textos">
        <span class="titulo-fila"${tituloRojo ? ' style="color:var(--red-text)"' : ''}>${esc(titulo)}</span>
        <span class="descripcion-fila">${esc(detalle)}</span>
      </span>
      ${flecha ? '<span class="icono flecha" data-icono="chevron_right"></span>' : ''}
    </button>`);
  el.addEventListener('click', () => {
    haptica.click();
    alPulsar();
  });
  muellePulsar(el);
  return el;
}

/** Tarjeta que se elige entre varias (Socios.TarjetaSeleccionable), con la marca arriba a la derecha. */
export function tarjetaElegible(contenido, { pendiente = false } = {}) {
  const el = html(`<button class="tarjeta elegible${pendiente ? ' pendiente' : ''}" type="button" role="radio" aria-checked="false"><span class="icono marca" data-icono="check_circle"></span></button>`);
  el.append(...(typeof contenido === 'string' ? [html(`<span>${contenido}</span>`)] : [contenido]));
  return el;
}

export function marcar(el, marcada) {
  el.classList.toggle('marcada', marcada);
  el.setAttribute('aria-checked', String(marcada));
}

/**
 * Grupo de opciones unidas (MaterialButtonToggleGroup): una sola marcada. [opciones]: { id, html, etiqueta, peso }.
 * [alElegir] recibe el id al tocar una (no al marcarla desde el código).
 */
export function grupoOpciones(opciones, alElegir, { alto = 50, tamano = 15 } = {}) {
  const grupo = html('<div class="grupo-opciones" role="radiogroup"></div>');
  const botones = new Map();
  for (const o of opciones) {
    const b = html(`<button class="opcion" type="button" role="radio" aria-checked="false"><span class="contenido-opcion">${o.html}</span></button>`);
    b.style.minHeight = `${alto}px`;
    b.style.fontSize = `${tamano}px`;
    if (o.peso) b.style.flexGrow = String(o.peso);
    if (o.etiqueta) b.setAttribute('aria-label', o.etiqueta);
    b.addEventListener('click', () => {
      if (b.classList.contains('marcada')) return;
      grupo.marcar(o.id);
      alElegir(o.id, b);
    });
    botones.set(o.id, b);
    grupo.append(b);
  }
  grupo.marcar = (id) => {
    for (const [k, b] of botones) {
      b.classList.toggle('marcada', k === id);
      b.setAttribute('aria-checked', String(k === id));
    }
  };
  grupo.boton = (id) => botones.get(id);
  grupo.marcado = () => [...botones].find(([, b]) => b.classList.contains('marcada'))?.[0] ?? null;
  return grupo;
}

/**
 * Los importes predefinidos y "Otro…", con su [campo] para escribirlo (OpcionesImporte). [alElegir] tras cada toque.
 */
export function opcionesImporte(importes, campo, alElegir = () => {}) {
  const rejilla = html('<div class="rejilla-importes" role="radiogroup"></div>');
  const botones = [...importes.map((i) => ({ valor: i, texto: eurosCompacto(i) })), { valor: null, texto: 'Otro…' }].map((o) => {
    const b = html(`<button class="opcion" type="button" role="radio" aria-checked="false">${esc(o.texto)}</button>`);
    b.valor = o.valor;
    rejilla.append(b);
    return b;
  });
  const otro = botones[botones.length - 1];
  const marcarBoton = (elegido) => botones.forEach((b) => {
    b.classList.toggle('marcada', b === elegido);
    b.setAttribute('aria-checked', String(b === elegido));
  });
  const api = {
    el: rejilla,
    otro,
    marcar: marcarBoton,
    esOtro: () => otro.classList.contains('marcada'),
    indice: () => botones.findIndex((b) => b.classList.contains('marcada')),
    ajustarCampo: () => campo.el.classList.toggle('oculto', !otro.classList.contains('marcada')),
    /** Marca el importe exacto; cualquier otro va a "Otro" con su importe. */
    elegir(importe) {
      const b = botones.find((x) => x.valor === importe);
      if (b) marcarBoton(b);
      else {
        marcarBoton(otro);
        campo.poner(importeInput(importe));
      }
    },
    /** El importe; el escrito, redondeado a céntimos. null si no es válido o pasa del tope. */
    elegido() {
      if (otro.classList.contains('marcada')) {
        const v = parseImporte(campo.valor());
        return v != null && v <= IMPORTE_MAXIMO ? redondearCentimos(v) : null;
      }
      return botones.find((b) => b.classList.contains('marcada'))?.valor ?? null;
    }
  };
  for (const b of botones) {
    b.addEventListener('click', () => {
      haptica.click();
      marcarBoton(b);
      if (b === otro) {
        desplegar(campo.el, true);
        setTimeout(() => campo.foco(), 50);
      } else {
        if (document.activeElement === campo.input) campo.input.blur();
        desplegar(campo.el, false);
      }
      alElegir();
    });
    muellePulsar(b);
  }
  return api;
}

/** Tarjeta con interruptor: tocar la tarjeta lo cambia y muestra u oculta [opciones]. */
export function tarjetaInterruptor({ iconoPlaca, tono, titulo, detalle, encendido = false, opciones = null, alCambiar = () => {}, tamanoPlaca = 44 }) {
  const el = html(`
    <div class="tarjeta tarjeta-interruptor" role="switch" tabindex="0" aria-checked="${encendido}" aria-label="${esc(titulo)}">
      <span class="placa tono-${tono}" style="width:${tamanoPlaca}px;height:${tamanoPlaca}px"><span class="icono" data-icono="${iconoPlaca}" style="width:${tamanoPlaca > 44 ? 24 : 22}px;height:${tamanoPlaca > 44 ? 24 : 22}px"></span></span>
      <span class="columna-textos">
        <span class="titulo-fila">${esc(titulo)}</span>
        <span class="descripcion-fila">${esc(detalle)}</span>
      </span>
      <span class="interruptor${encendido ? ' encendido' : ''}" aria-hidden="true"></span>
    </div>`);
  const interruptor = el.querySelector('.interruptor');
  const api = {
    el,
    encendido: () => interruptor.classList.contains('encendido'),
    poner(v) {
      interruptor.classList.toggle('encendido', v);
      el.setAttribute('aria-checked', String(v));
      if (opciones) opciones.classList.toggle('oculto', !v);
    },
    cambiar(v) {
      interruptor.classList.toggle('encendido', v);
      el.setAttribute('aria-checked', String(v));
      if (opciones) desplegar(opciones, v);
      if (!v && document.activeElement?.matches('input, textarea')) document.activeElement.blur();
      alCambiar(v);
    }
  };
  const alternar = () => {
    haptica.toggle();
    api.cambiar(!api.encendido());
  };
  el.addEventListener('click', alternar);
  el.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      alternar();
    }
  });
  if (opciones) opciones.classList.toggle('oculto', !encendido);
  return api;
}

/**
 * El texto encoge lo justo para caber en una línea, de [max] a [min] px (autoSizeTextType="uniform"): "+10.044,14 €"
 * no debe cortarse. Se vuelve a ajustar cuando cambia el texto o el ancho.
 *
 * Sin tocar el diseño de la página: el ancho disponible lo da un ResizeObserver y el del texto se calcula con
 * measureText de un lienzo. Antes se leían clientWidth y scrollWidth tras cada cambio, y como los importes cuentan
 * hasta su valor (un texto nuevo en cada fotograma), el navegador tenía que recalcular el diseño en cada fotograma.
 */
const medidorTexto = document.createElement('canvas').getContext('2d');

export function autoajustar(el, max, min) {
  el.style.whiteSpace = 'nowrap';
  el.style.overflow = 'hidden';
  let disponible = 0;
  let estilo = null;
  let ultimo = null;
  const ajustar = () => {
    if (!disponible || !el.isConnected) return;
    // La fuente, el peso y el espaciado se leen una vez (no cambian con el texto ni con el tema).
    if (!estilo) {
      const cs = getComputedStyle(el);
      estilo = {
        fuente: `${cs.fontStyle} ${cs.fontWeight} ${max}px ${cs.fontFamily}`,
        // En em: el letter-spacing calculado está en px para el tamaño de ahora.
        espaciado: (parseFloat(cs.letterSpacing) || 0) / (parseFloat(cs.fontSize) || max),
        tabulares: /tnum|tabular-nums/.test(cs.fontFeatureSettings + cs.fontVariantNumeric)
      };
    }
    let texto = el.textContent;
    // Con cifras tabulares todas miden lo mismo que el 0.
    if (estilo.tabulares) texto = texto.replace(/d/g, '0');
    medidorTexto.font = estilo.fuente;
    const necesario = (medidorTexto.measureText(texto).width + estilo.espaciado * max * [...texto].length) * 1.02;
    const tamano = necesario > disponible ? Math.max(min, Math.floor(((max * disponible) / necesario) * 2) / 2) : max;
    if (tamano !== ultimo) {
      ultimo = tamano;
      el.style.fontSize = `${tamano}px`;
    }
  };
  // El texto cambia: en el mismo fotograma (es solo una cuenta, no hace falta esperar al siguiente).
  new MutationObserver(ajustar).observe(el, { childList: true, characterData: true, subtree: true });
  new ResizeObserver(([entrada]) => {
    disponible = entrada.contentBoxSize?.[0]?.inlineSize ?? entrada.contentRect.width;
    ajustar();
  }).observe(el);
  // Hasta que carga Geist se mediría con otra fuente.
  document.fonts?.ready.then(() => {
    estilo = null;
    ajustar();
  });
}

export { pulsar, muellePulsar };

// SocioAdapter.kt y ListaSemana.kt: filas de socios (con su interruptor) y cabeceras de día. Las filas se conservan
// por id entre un pintado y otro, como hace la lista de Android: solo se anima lo que cambia.
import { calcularBaseX2, Centimos } from './calculos.js';
import { diaPorId, rangoCorto } from './fechas.js';
import { eurosCompacto, esc } from './formato.js';
import { icono } from './iconos.js';
import { nombreVisible } from './estado.js';
import { haptica } from './movimiento.js';

/** Las filas agrupadas por día (de lunes a viernes), cada día con su cabecera y sus socios por orden de alta. */
export function agrupadasPorDia(socios) {
  const porDia = Map.groupBy(socios, (s) => s.dia);
  const filas = [];
  for (const dia of [...porDia.keys()].sort((a, b) => a - b)) {
    const delDia = porDia.get(dia);
    const hechos = delDia.filter((s) => s.hecho);
    filas.push({
      id: `dia-${dia}`,
      cabecera: true,
      dia,
      socios: delDia.length,
      hechos: hechos.length,
      ganancia: Centimos.suma(hechos, (s) => calcularBaseX2(s.colaboracion))
    });
    delDia.toSorted((a, b) => a.timestamp - b.timestamp).forEach((s) => filas.push({ id: s.id, socio: s }));
  }
  return filas;
}

export const sinAgrupar = (socios) => socios.map((s) => ({ id: s.id, socio: s }));

const COLORES_PLACA = {
  hecho: ['#13825B', '#0B5A3F'],
  pendiente: ['#A85F12', '#8A470B']
};

const hexARgb = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const mezclaHex = (a, b, f) => {
  const x = hexARgb(a), y = hexARgb(b);
  return `rgb(${x.map((v, i) => Math.round(v + (y[i] - v) * f)).join(',')})`;
};

/**
 * Una lista de socios en [contenedor]. [mostrarSemana]: en la búsqueda y en los pendientes cada uno dice su semana
 * y tocarlo llama a [alAbrir] en lugar de editarlo.
 */
export class ListaSocios {
  constructor(contenedor, { alCambiarHecho, alEditar, alEliminar, alAcciones, mostrarSemana = false, alAbrir = null }) {
    this.contenedor = contenedor;
    this.op = { alCambiarHecho, alEditar, alEliminar, alAcciones, mostrarSemana, alAbrir };
    this.nodos = new Map();
    this.datos = new Map();
  }

  /** Pinta [filas]. [modo]: 'nada' (sin animar), 'cascada' (entran una tras otra) o 'normal' (las nuevas aparecen). */
  mostrar(filas, modo = 'normal') {
    const vivos = new Set(filas.map((f) => f.id));
    for (const [id, nodo] of this.nodos) {
      if (!vivos.has(id)) {
        nodo.remove();
        this.nodos.delete(id);
        this.datos.delete(id);
      }
    }
    let anterior = null;
    let orden = 0;
    for (const fila of filas) {
      let nodo = this.nodos.get(fila.id);
      const nuevo = !nodo;
      if (nuevo) {
        nodo = fila.cabecera ? this.crearCabecera() : this.crearSocio();
        this.nodos.set(fila.id, nodo);
      }
      const previo = this.datos.get(fila.id);
      if (fila.cabecera) this.pintarCabecera(nodo, fila);
      else this.pintarSocio(nodo, fila.socio, previo?.socio, nuevo);
      this.datos.set(fila.id, fila);
      const posicion = anterior ? anterior.nextSibling : this.contenedor.firstChild;
      if (posicion !== nodo) this.contenedor.insertBefore(nodo, posicion);
      anterior = nodo;
      if (modo === 'cascada') {
        nodo.classList.remove('entra', 'aparece');
        void nodo.offsetWidth;
        nodo.style.animationDelay = `${orden * 50}ms`;
        nodo.classList.add('entra');
      } else if (modo === 'normal' && nuevo) {
        nodo.classList.remove('entra');
        nodo.style.animationDelay = '';
        nodo.classList.add('aparece');
      }
      orden++;
    }
  }

  crearCabecera() {
    const el = document.createElement('div');
    el.className = 'cabecera-dia';
    el.tabIndex = 0;
    el.innerHTML = '<span class="nombre" aria-hidden="true"></span><span class="detalle" aria-hidden="true"></span>';
    return el;
  }

  pintarCabecera(el, f) {
    const dia = diaPorId(f.dia).completo;
    const ganancia = eurosCompacto(f.ganancia, '+');
    el.querySelector('.nombre').textContent = dia;
    el.querySelector('.detalle').textContent = `${f.hechos} de ${f.socios} · ${ganancia}`;
    el.setAttribute('aria-label', `${dia}: ${f.hechos} de ${f.socios} ${f.socios === 1 ? 'socio hecho' : 'socios hechos'}, ${ganancia}`);
  }

  crearSocio() {
    const el = document.createElement('div');
    el.className = 'socio';
    el.tabIndex = 0;
    el.setAttribute('role', 'button');
    el.innerHTML = `
      <div class="placa-importe"><span class="cuota cifra"></span><span class="base"></span></div>
      <div class="datos">
        <div class="titulo-fila nombre una-linea"></div>
        <div class="linea-estado"><span class="dia"></span><span class="separador" aria-hidden="true">·</span><span class="estado"></span></div>
        <div class="notas dos-lineas oculto"></div>
      </div>
      <div class="contenedor-interruptor"><button class="interruptor" type="button" role="switch" aria-label="Conseguido"></button></div>`;
    const interruptor = el.querySelector('.interruptor');
    const actual = () => this.datos.get(el.dataset.id)?.socio;
    interruptor.addEventListener('click', (e) => {
      e.stopPropagation();
      const s = actual();
      if (!s) return;
      haptica.toggle();
      const hecho = !interruptor.classList.contains('encendido');
      interruptor.classList.toggle('encendido', hecho);
      interruptor.setAttribute('aria-checked', String(hecho));
      this.op.alCambiarHecho(s, hecho);
    });
    // Tocar la tarjeta la edita (o abre su semana); mantenerla pulsada abre editar y eliminar.
    let temporizador = 0;
    let largo = false;
    let x0 = 0, y0 = 0;
    el.addEventListener('pointerdown', (e) => {
      if (e.target.closest('.contenedor-interruptor')) return;
      largo = false;
      x0 = e.clientX;
      y0 = e.clientY;
      el.classList.add('pulsado');
      el.style.transform = 'scale(0.97)';
      el.style.transitionDuration = '120ms';
      clearTimeout(temporizador);
      temporizador = setTimeout(() => {
        largo = true;
        haptica.confirmar();
        soltar();
        const s = actual();
        if (s) this.op.alAcciones(s);
      }, 500);
    });
    const soltar = () => {
      clearTimeout(temporizador);
      el.style.transform = '';
      el.style.transitionDuration = '';
    };
    el.addEventListener('pointermove', (e) => {
      if (Math.abs(e.clientX - x0) > 10 || Math.abs(e.clientY - y0) > 10) soltar();
    });
    el.addEventListener('pointerup', soltar);
    el.addEventListener('pointercancel', soltar);
    el.addEventListener('pointerleave', soltar);
    el.addEventListener('contextmenu', (e) => e.preventDefault());
    el.addEventListener('click', (e) => {
      if (largo || e.target.closest('.contenedor-interruptor')) return;
      const s = actual();
      if (!s) return;
      haptica.click();
      (this.op.alAbrir ?? this.op.alEditar)(s);
    });
    el.addEventListener('keydown', (e) => {
      if (e.target !== el) return;
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        el.click();
      } else if (e.key === 'Delete') {
        const s = actual();
        if (s) this.op.alEliminar(s);
      } else if (e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey)) {
        e.preventDefault();
        const s = actual();
        if (s) this.op.alAcciones(s);
      }
    });
    return el;
  }

  pintarSocio(el, s, previo, nuevo) {
    el.dataset.id = s.id;
    const base = calcularBaseX2(s.colaboracion);
    el.querySelector('.cuota').textContent = eurosCompacto(s.colaboracion);
    el.querySelector('.base').textContent = `${eurosCompacto(base)} base`;
    const dia = diaPorId(s.dia);
    el.querySelector('.dia').textContent = this.op.mostrarSemana ? `${dia.corto} · ${rangoCorto(s.semanaKey)}` : dia.completo;
    el.querySelector('.nombre').textContent = nombreVisible(s);
    const notas = el.querySelector('.notas');
    notas.textContent = s.notas;
    notas.classList.toggle('oculto', !s.notas.trim());
    const interruptor = el.querySelector('.interruptor');
    interruptor.classList.toggle('encendido', s.hecho);
    interruptor.setAttribute('aria-checked', String(s.hecho));
    const estado = el.querySelector('.estado');
    estado.textContent = s.hecho ? `Suma ${eurosCompacto(base)}` : 'Pendiente';
    estado.style.color = s.hecho ? 'var(--primary)' : 'var(--orange-pending)';
    const etiquetaAccion = this.op.alAbrir ? 'Abrir su semana' : 'Editar socio';
    el.setAttribute('aria-label', `${nombreVisible(s)}, ${dia.completo}, ${estado.textContent}. ${etiquetaAccion}`);
    // Sin fundido al reutilizar la fila para otro socio; con fundido de 300 ms si solo cambia su estado.
    const cambiaEstado = !nuevo && previo && previo.id === s.id && previo.hecho !== s.hecho;
    this.pintarPlaca(el, s.hecho, cambiaEstado);
    estado.style.transition = cambiaEstado ? '' : 'none';
    if (!cambiaEstado) requestAnimationFrame(() => (estado.style.transition = ''));
  }

  pintarPlaca(el, hecho, animar) {
    const placa = el.querySelector('.placa-importe');
    const destino = hecho ? COLORES_PLACA.hecho : COLORES_PLACA.pendiente;
    const origen = hecho ? COLORES_PLACA.pendiente : COLORES_PLACA.hecho;
    placa._fundido?.cancelar?.();
    if (!animar) {
      placa.style.setProperty('--c0', destino[0]);
      placa.style.setProperty('--c1', destino[1]);
      return;
    }
    const inicio = performance.now();
    let vivo = true;
    const paso = (ahora) => {
      if (!vivo) return;
      const f = Math.min(1, (ahora - inicio) / 300);
      placa.style.setProperty('--c0', mezclaHex(origen[0], destino[0], f));
      placa.style.setProperty('--c1', mezclaHex(origen[1], destino[1], f));
      if (f < 1) requestAnimationFrame(paso);
    };
    placa._fundido = { cancelar: () => (vivo = false) };
    requestAnimationFrame(paso);
  }
}

/** Placa del socio en otros sitios (panel de acciones): el degradado verde o ámbar. */
export const degradadoSocio = (hecho) => `linear-gradient(135deg, ${(hecho ? COLORES_PLACA.hecho : COLORES_PLACA.pendiente).join(', ')})`;


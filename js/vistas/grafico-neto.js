// GraficoNetoView.kt: columnas del neto por semana, mes o año. Si no caben, se desplazan con el dedo (empieza en lo
// más reciente) y los bordes se difuminan. La destacada en el verde de la app, el resto en un verde más suave, lo
// negativo en rojo; escala fija a la derecha, línea discontinua con la media y marcas de año.
import { escalaPara, Centimos, redondearCentimos } from '../calculos.js';
import { eurosCompacto } from '../formato.js';
import { color, conAlfa, css, fuente, metricas, preparar, rectRedondo, alCambiarTamano } from '../lienzo.js';
import { animarValor, haptica, LARGA_MS } from '../movimiento.js';

const HUECO_MIN = 30;
const ANCHO_MAX_BARRA = 22;
const SEPARACION_MIN = 3;
const RADIO = 5;
const ALTO_EJE = 22;
const ALTO_MARCA = 18;
const ALTO_ROTULO = 18;
const ALTO_AVISO = 28;
const ANCHO_DIFUMINADO = 28;
const COLUMNAS_MIN = 12;
const MARGEN_TOQUE = 8;

export class GraficoNeto {
  constructor(canvas) {
    this.canvas = canvas;
    this.columnas = [];
    this.destacada = -1;
    this.seleccionada = -1;
    this.progreso = 1;
    this.animacion = null;
    this.media = null;
    this.alSeleccionar = null;
    this.desplazamiento = 0;
    this.pegadoAlFinal = true;
    this.anchoEtiqueta = 0;
    this.inercia = 0;
    alCambiarTamano(canvas, () => this.dibujar());
    this.escucharToques();
  }

  /** Muestra [nuevas] columnas ({ etiqueta, etiquetaLarga, neto, separador }); con [animar], crecen desde la base. */
  mostrar(nuevas, destacada, animar) {
    const mismas = nuevas.map((c) => c.etiquetaLarga).join('|') === this.columnas.map((c) => c.etiquetaLarga).join('|');
    this.columnas = nuevas;
    const ctx = this.canvas.getContext('2d');
    ctx.font = fuente(500, 10.5);
    this.anchoEtiqueta = Math.max(0, ...nuevas.map((c) => ctx.measureText(c.etiqueta).width)) + 6;
    this.destacada = destacada;
    this.prepararDatos(ctx);
    const conDatos = nuevas.filter((c) => c.neto !== 0);
    this.media = conDatos.length >= 2 ? redondearCentimos(Centimos.suma(conDatos, (c) => c.neto) / conDatos.length) : null;
    if (!mismas) {
      this.seleccionada = -1;
      this.pararInercia();
      this.pegadoAlFinal = true;
    }
    this.animacion?.cancelar();
    if (!animar) {
      this.progreso = 1;
      this.dibujar();
      return;
    }
    this.animacion = animarValor(0, 1, LARGA_MS, (v) => {
      this.progreso = v;
      this.dibujar();
    });
  }

  prepararDatos(ctx) {
    this.escala = escalaPara(this.columnas.map((c) => c.neto));
    this.textosGuias = this.escala.guias.map((g) => eurosCompacto(g));
    ctx.font = fuente(600, 10);
    this.anchoEscala = Math.max(0, ...this.textosGuias.map((t) => ctx.measureText(t).width)) + 8;
    this.textosNeto = this.columnas.map((c) => eurosCompacto(c.neto));
    let max = -1;
    this.columnas.forEach((c, i) => {
      if (max < 0 || c.neto > this.columnas[max].neto) max = i;
    });
    this.indiceMaximo = max >= 0 && this.columnas[max].neto > 0 ? max : -1;
    this.marcas = this.columnas.map((c, i) => (c.separador != null ? i : -1)).filter((i) => i >= 0);
    ctx.font = fuente(600, 10.5);
    this.anchosMarca = this.marcas.map((i) => ctx.measureText(this.columnas[i].separador).width);
  }

  get anchoArea() {
    return this.canvas.clientWidth - (this.anchoEscala ?? 0);
  }

  get hueco() {
    if (!this.columnas.length) return 0;
    return Math.max(HUECO_MIN, this.anchoEtiqueta, this.anchoArea / Math.max(this.columnas.length, COLUMNAS_MIN));
  }

  get maxDesplazamiento() {
    return Math.max(0, this.columnas.length * this.hueco - this.anchoArea);
  }

  get sePuedeDesplazar() {
    return this.anchoArea > 0 ? this.maxDesplazamiento > 0.5 : this.columnas.length > COLUMNAS_MIN;
  }

  get origen() {
    return Math.max(0, this.anchoArea - this.columnas.length * this.hueco) - this.desplazamiento;
  }

  centroDe(i) {
    return this.origen + this.hueco * (i + 0.5);
  }

  desplazarA(v) {
    this.desplazamiento = Math.min(this.maxDesplazamiento, Math.max(0, v));
    this.pegadoAlFinal = this.desplazamiento >= this.maxDesplazamiento - 0.5;
    this.dibujar();
  }

  dibujar() {
    const w = this.canvas.clientWidth;
    const h = this.canvas.clientHeight;
    if (!w) return;
    const ctx = preparar(this.canvas, w, h);
    if (!this.columnas.length) return;
    const c = {
      barra: color('--grafico-barra'),
      suave: color('--grafico-barra-suave'),
      negativo: color('--red-text'),
      eje: color('--divider'),
      texto: color('--text-main'),
      textoSuave: color('--text-sub'),
      fondo: color('--surface-card'),
      media: color('--green-text-soft')
    };
    this.desplazamiento = this.pegadoAlFinal ? this.maxDesplazamiento : Math.min(this.maxDesplazamiento, Math.max(0, this.desplazamiento));
    const e = this.escala;
    const arriba = ALTO_MARCA + ALTO_AVISO + ALTO_ROTULO;
    const abajo = h - ALTO_EJE;
    const altoUtil = abajo - arriba;
    const base = arriba + altoUtil * (e.techo / e.rango);
    const derecha = this.anchoArea;
    const yDe = (v) => base - altoUtil * (v / e.rango);

    // Escala y líneas de guía (la del cero, continua).
    ctx.font = fuente(600, 10);
    const m = metricas(ctx);
    ctx.textAlign = 'right';
    e.guias.forEach((valor, g) => {
      const y = yDe(valor);
      ctx.strokeStyle = css(c.eje);
      ctx.lineWidth = 1;
      ctx.setLineDash(e.esCero(valor) ? [] : [3, 4]);
      ctx.beginPath();
      ctx.moveTo(0, Math.round(y) + 0.5);
      ctx.lineTo(derecha, Math.round(y) + 0.5);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle = css(c.textoSuave);
      ctx.fillText(this.textosGuias[g], w, y + (m.asc - m.desc) / 2);
    });

    ctx.save();
    ctx.beginPath();
    ctx.rect(0, 0, derecha, h);
    ctx.clip();
    this.dibujarColumnas(ctx, c, base, altoUtil, abajo, derecha);
    if (this.media != null) {
      const y = yDe(this.media);
      ctx.strokeStyle = css(c.media);
      ctx.lineWidth = 1.5;
      ctx.setLineDash([6, 4]);
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(derecha, y);
      ctx.stroke();
      ctx.setLineDash([]);
    }
    this.dibujarRotulos(ctx, c, base, altoUtil, abajo, derecha);
    this.dibujarMarcas(ctx, c, base);
    this.dibujarDifuminados(ctx, c, derecha, h);
    if (this.seleccionada >= 0 && this.seleccionada < this.columnas.length) this.dibujarAviso(ctx, c, base, altoUtil, derecha);
    ctx.restore();
  }

  altoDe(neto, altoUtil) {
    return altoUtil * (Math.abs(neto) / this.escala.rango);
  }

  dibujarColumnas(ctx, c, base, altoUtil, abajo, derecha) {
    const hueco = this.hueco;
    if (hueco <= 0) return;
    const anchoBarra = Math.min(ANCHO_MAX_BARRA, hueco - SEPARACION_MIN * 2);
    const paso = Math.max(1, Math.ceil(this.anchoEtiqueta / hueco));
    const anclaje = this.destacada >= 0 && this.destacada < this.columnas.length ? this.destacada : this.columnas.length - 1;
    const primera = Math.max(0, Math.floor((0 - this.origen) / hueco) - 1);
    const ultima = Math.min(this.columnas.length - 1, Math.ceil((derecha - this.origen) / hueco));
    ctx.font = fuente(500, 10.5);
    ctx.textAlign = 'center';
    for (let i = primera; i <= ultima; i++) {
      const col = this.columnas[i];
      const centro = this.centroDe(i);
      const alto = this.altoDe(col.neto, altoUtil) * this.progreso;
      ctx.fillStyle = css(col.neto < 0 ? c.negativo : i === this.destacada || i === this.seleccionada ? c.barra : c.suave);
      if (alto > 0.5) {
        const r = Math.min(RADIO, alto / 2);
        if (col.neto >= 0) rectRedondo(ctx, centro - anchoBarra / 2, base - alto, anchoBarra, alto, [r, r, 0, 0]);
        else rectRedondo(ctx, centro - anchoBarra / 2, base, anchoBarra, alto, [0, 0, r, r]);
        ctx.fill();
      }
      if ((((anclaje - i) % paso) + paso) % paso === 0) {
        ctx.fillStyle = css(i === this.destacada ? c.texto : c.textoSuave);
        ctx.fillText(col.etiqueta, centro, abajo + ALTO_EJE - 6);
      }
    }
  }

  dibujarRotulos(ctx, c, base, altoUtil, abajo, derecha) {
    if (this.progreso < 1) return;
    ctx.font = fuente(600, 11);
    const d = this.colocarRotulo(ctx, this.destacada, base, altoUtil, abajo, derecha);
    let mx = this.indiceMaximo !== this.destacada ? this.colocarRotulo(ctx, this.indiceMaximo, base, altoUtil, abajo, derecha) : null;
    if (d && mx && !(mx.caja[2] < d.caja[0] || mx.caja[0] > d.caja[2] || mx.caja[3] < d.caja[1] || mx.caja[1] > d.caja[3])) mx = null;
    ctx.textAlign = 'center';
    ctx.lineJoin = 'round';
    for (const r of [d, mx]) {
      if (!r) continue;
      ctx.strokeStyle = css(c.fondo);
      ctx.lineWidth = 4;
      ctx.strokeText(r.texto, r.centro, r.linea);
      ctx.fillStyle = css(c.texto);
      ctx.fillText(r.texto, r.centro, r.linea);
    }
  }

  colocarRotulo(ctx, indice, base, altoUtil, abajo, derecha) {
    const col = this.columnas[indice];
    if (!col || indice === this.seleccionada || col.neto === 0) return null;
    const centroBarra = this.centroDe(indice);
    if (centroBarra < 0 || centroBarra > derecha) return null;
    const alto = this.altoDe(col.neto, altoUtil);
    const linea = Math.min(abajo, col.neto >= 0 ? base - alto - 6 : base + alto + ALTO_ROTULO - 4);
    const texto = this.textosNeto[indice];
    const mitad = ctx.measureText(texto).width / 2;
    const centro = Math.min(derecha - mitad, Math.max(mitad, centroBarra));
    const m = metricas(ctx);
    return { texto, linea, centro, caja: [centro - mitad, linea - m.asc, centro + mitad, linea + m.desc] };
  }

  dibujarMarcas(ctx, c, base) {
    const hueco = this.hueco;
    ctx.font = fuente(600, 10.5);
    const m = metricas(ctx);
    ctx.strokeStyle = css(c.eje);
    ctx.lineWidth = 1;
    for (const i of this.marcas) {
      const x = this.origen + hueco * i;
      if (i > 0 && x > 0) {
        ctx.setLineDash([3, 4]);
        ctx.beginPath();
        ctx.moveTo(x, ALTO_MARCA);
        ctx.lineTo(x, base);
        ctx.stroke();
        ctx.setLineDash([]);
      }
    }
    const margen = 4;
    let fija = -1;
    this.marcas.forEach((i, k) => {
      if (this.origen + hueco * i <= margen) fija = k;
    });
    ctx.textAlign = 'left';
    this.marcas.forEach((i, k) => {
      const texto = this.columnas[i].separador;
      const ancho = this.anchosMarca[k];
      let x;
      if (k === fija) {
        const siguiente = k + 1 < this.marcas.length ? this.origen + hueco * this.marcas[k + 1] : Infinity;
        x = Math.min(Math.max(this.origen, 0) + margen, siguiente - ancho - margen * 3);
      } else {
        x = this.origen + hueco * i + margen;
      }
      if (k !== fija && x < margen) return;
      ctx.fillStyle = css(c.fondo);
      ctx.fillRect(x - margen, 0, ancho + margen * 2, ALTO_MARCA);
      ctx.fillStyle = css(c.textoSuave);
      ctx.fillText(texto, x, m.asc);
    });
  }

  dibujarDifuminados(ctx, c, derecha, h) {
    const transparente = conAlfa(c.fondo, 0);
    if (this.desplazamiento > 0.5) {
      const g = ctx.createLinearGradient(0, 0, ANCHO_DIFUMINADO, 0);
      g.addColorStop(0, css(c.fondo));
      g.addColorStop(1, css(transparente));
      ctx.fillStyle = g;
      ctx.fillRect(0, ALTO_MARCA, ANCHO_DIFUMINADO, h);
    }
    if (this.desplazamiento < this.maxDesplazamiento - 0.5) {
      const g = ctx.createLinearGradient(derecha, 0, derecha - ANCHO_DIFUMINADO, 0);
      g.addColorStop(0, css(c.fondo));
      g.addColorStop(1, css(transparente));
      ctx.fillStyle = g;
      ctx.fillRect(derecha - ANCHO_DIFUMINADO, ALTO_MARCA, ANCHO_DIFUMINADO, h);
    }
  }

  dibujarAviso(ctx, c, base, altoUtil, derecha) {
    const i = this.seleccionada;
    const col = this.columnas[i];
    const texto = `${col.etiquetaLarga} · ${this.textosNeto[i]}`;
    ctx.font = fuente(600, 12);
    const ancho = ctx.measureText(texto).width + 20;
    const alto = 24;
    const izquierda = Math.min(Math.max(0, derecha - ancho), Math.max(0, this.centroDe(i) - ancho / 2));
    const cima = col.neto >= 0 ? base - this.altoDe(col.neto, altoUtil) : base;
    const y = Math.max(ALTO_MARCA, cima - alto - 8);
    ctx.fillStyle = css(c.texto);
    rectRedondo(ctx, izquierda, y, ancho, alto, alto / 2);
    ctx.fill();
    const m = metricas(ctx);
    ctx.fillStyle = css(c.fondo);
    ctx.textAlign = 'center';
    ctx.fillText(texto, izquierda + ancho / 2, y + alto / 2 + (m.asc - m.desc) / 2);
  }

  // --- Toques: tocar elige; arrastrar en horizontal desplaza (con inercia) o recorre si caben todas ---

  escucharToques() {
    const cv = this.canvas;
    let x0 = 0, y0 = 0, ultimoX = 0, arrastrando = false, recorriendo = false, muestras = [];
    cv.addEventListener('pointerdown', (e) => {
      if (!this.columnas.length) return;
      this.pararInercia();
      x0 = ultimoX = e.clientX;
      y0 = e.clientY;
      arrastrando = recorriendo = false;
      muestras = [{ x: e.clientX, t: e.timeStamp }];
    });
    cv.addEventListener('pointermove', (e) => {
      if (!(e.buttons & 1) && e.pointerType === 'mouse') return;
      if (!muestras.length) return;
      const dx = Math.abs(e.clientX - x0);
      if (!arrastrando && !recorriendo && dx > MARGEN_TOQUE && dx > Math.abs(e.clientY - y0)) {
        if (this.sePuedeDesplazar) arrastrando = true;
        else recorriendo = true;
        cv.setPointerCapture?.(e.pointerId);
      }
      if (arrastrando) this.desplazarA(this.desplazamiento + ultimoX - e.clientX);
      else if (recorriendo) this.seleccionar(e.clientX);
      ultimoX = e.clientX;
      muestras.push({ x: e.clientX, t: e.timeStamp });
      if (muestras.length > 6) muestras.shift();
    });
    cv.addEventListener('pointerup', (e) => {
      if (!muestras.length) return;
      if (arrastrando) {
        const a = muestras[0], b = muestras[muestras.length - 1];
        const v = b.t > a.t ? -((b.x - a.x) / (b.t - a.t)) : 0; // px/ms
        if (Math.abs(v) > 0.05) this.lanzar(v);
      } else if (!recorriendo) {
        this.seleccionar(e.clientX);
      }
      arrastrando = recorriendo = false;
      muestras = [];
    });
    cv.addEventListener('pointercancel', () => {
      arrastrando = recorriendo = false;
      muestras = [];
    });
  }

  lanzar(velocidad) {
    let v = velocidad;
    let ultimo = performance.now();
    const paso = (ahora) => {
      const dt = Math.min(32, ahora - ultimo);
      ultimo = ahora;
      this.desplazarA(this.desplazamiento + v * dt);
      v *= Math.exp(-dt / 325);
      if (Math.abs(v) > 0.02 && this.desplazamiento > 0 && this.desplazamiento < this.maxDesplazamiento) this.inercia = requestAnimationFrame(paso);
      else this.inercia = 0;
    };
    this.inercia = requestAnimationFrame(paso);
  }

  pararInercia() {
    if (this.inercia) cancelAnimationFrame(this.inercia);
    this.inercia = 0;
  }

  /** Toda la franja vertical de una columna es su zona táctil. */
  seleccionar(clientX) {
    const x = clientX - this.canvas.getBoundingClientRect().left;
    const hueco = this.hueco;
    if (x < 0 || x > this.anchoArea || hueco <= 0) return;
    const i = Math.floor((x - this.origen) / hueco);
    if (i < 0 || i >= this.columnas.length || i === this.seleccionada) return;
    this.seleccionada = i;
    haptica.click();
    this.alSeleccionar?.(this.columnas[i]);
    this.dibujar();
  }
}

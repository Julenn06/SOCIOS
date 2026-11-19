// ProgresoBonusView.kt: la barra con un hito por tramo del bonus (5 → 50 €, 7 → 100 €…). Al avanzar crece, cada hito
// que se alcanza da un salto y, al terminar, un brillo recorre lo conseguido mientras el siguiente hito late.
import { calcularBonusSemanal, siguienteMetaBonus, tramosHasta } from '../calculos.js';
import { eurosCompacto } from '../formato.js';
import { color, conAlfa, css, fuente, metricas, mezclar, preparar, rectRedondo, alCambiarTamano, medida } from '../lienzo.js';
import { activas, curva, overshoot } from '../movimiento.js';

const TRAMO_FIJO_MAX = 10;
const VENTANA = 9;
const DURACION_MS = 650;
const DURACION_SEMANA_MS = 750;
const REBOTE = 1.4;
const TRANSICION_HITO = 0.35;
const DURACION_SALTO_MS = 380;
const DURACION_EFECTO_MS = 1800;
const FRACCION_BRILLO = 0.55;
const LATIDOS = 2;
const SEPARACION_IMPORTE = 5;
const MARGEN_INFERIOR = 3.5;

export class ProgresoBonus {
  constructor(canvas) {
    this.canvas = canvas;
    this.grosor = 10;
    this.inicio = 0;
    this.fin = 10;
    this.hitos = [5, 7, 10];
    this.importes = this.importesDe(this.hitos);
    this.mostrado = 0;
    this.animacion = null;
    this.objetivoAnimado = -1;
    this.efecto = null;
    this.inicioEfecto = 0;
    this.saltos = new Map();
    this.primeraVez = true;
    this.id = 0;
    this.margenHalo = 6;
    this.medir();
    alCambiarTamano(canvas, () => {
      this.medir();
      this.dibujar();
    });
  }

  importesDe(hitos) {
    return hitos.map((h) => eurosCompacto(calcularBonusSemanal(h), '+'));
  }

  medir() {
    const ctx = this.canvas.getContext('2d');
    ctx.font = fuente(600, 11);
    this.radioHito = Math.max(13, ctx.measureText('00').width / 2 + 4.5);
    ctx.font = fuente(600, 11.5);
    const { asc, desc } = metricas(ctx);
    this.altoImporte = asc + desc;
    const alto = this.margenHalo + this.radioHito * 2 + SEPARACION_IMPORTE + this.altoImporte + MARGEN_INFERIOR;
    this.canvas.style.height = `${Math.ceil(alto)}px`;
  }

  /** Muestra [socios] hechos; con [animar], la barra avanza (o retrocede) hasta ellos. */
  mostrar(socios, animar, cambioDeSemana = false) {
    const siguiente = siguienteMetaBonus(socios)[0];
    this.fin = Math.max(TRAMO_FIJO_MAX, siguiente);
    this.inicio = this.fin <= TRAMO_FIJO_MAX ? 0 : this.fin - VENTANA;
    this.hitos = tramosHasta(this.fin).filter((t) => t > this.inicio);
    this.importes = this.importesDe(this.hitos);

    const objetivo = socios;
    if (animar && this.animacion && this.objetivoAnimado === objetivo) return;
    this.animacion = null;
    this.objetivoAnimado = objetivo;
    if (!animar || !activas()) {
      this.mostrado = objetivo;
      this.saltos.clear();
      this.pedirDibujo();
      if (this.primeraVez) this.empezarEfecto();
      this.primeraVez = false;
      return;
    }
    this.primeraVez = false;
    const desde = this.mostrado;
    if (desde === objetivo) {
      this.pedirDibujo();
      return;
    }
    const sube = objetivo > desde;
    // El rebote no puede cruzar un hito sin alcanzarlo de verdad.
    const bajoObjetivo = this.hitos.findLast((h) => h <= objetivo);
    const sobreObjetivo = this.hitos.find((h) => h > objetivo);
    const minimo = Math.min(bajoObjetivo ?? this.inicio, desde);
    const maximo = Math.max(sobreObjetivo != null ? sobreObjetivo - 0.05 : this.fin, desde);
    const duracion = cambioDeSemana ? DURACION_SEMANA_MS : DURACION_MS;
    const interp = cambioDeSemana ? overshoot(REBOTE) : curva;
    const anim = { desde, objetivo, sube, minimo, maximo, duracion, interp, empezo: performance.now() };
    this.animacion = anim;
    this.pedirDibujo();
  }

  empezarEfecto() {
    if (!activas()) return;
    this.efecto = 0;
    this.inicioEfecto = performance.now();
    this.pedirDibujo();
  }

  pedirDibujo() {
    if (this.id) return;
    this.id = requestAnimationFrame(() => {
      this.id = 0;
      this.paso();
    });
  }

  paso() {
    const ahora = performance.now();
    let seguir = false;
    const a = this.animacion;
    if (a) {
      const t = Math.min(1, (ahora - a.empezo) / a.duracion);
      const valor = Math.min(a.maximo, Math.max(a.minimo, a.desde + (a.objetivo - a.desde) * a.interp(t)));
      for (const h of this.hitos) {
        if (a.sube && h > this.mostrado && h <= valor) this.saltos.set(h, [ahora, true]);
        if (!a.sube && h <= this.mostrado && h > valor) this.saltos.set(h, [ahora, false]);
      }
      this.mostrado = valor;
      if (t >= 1) {
        this.mostrado = a.objetivo;
        this.animacion = null;
        if (a.sube) this.empezarEfecto();
      } else {
        seguir = true;
      }
    }
    if (this.efecto != null) {
      const e = (ahora - this.inicioEfecto) / DURACION_EFECTO_MS;
      if (e >= 1) this.efecto = null;
      else {
        this.efecto = e;
        seguir = true;
      }
    }
    if (this.dibujar(ahora)) seguir = true;
    if (seguir) this.pedirDibujo();
  }

  /** Devuelve true si algún hito sigue saltando. */
  dibujar(ahora = performance.now()) {
    const ancho = medida(this.canvas).ancho;
    const alto = medida(this.canvas).alto;
    if (!ancho) return false;
    const ctx = preparar(this.canvas, ancho, alto);
    const principal = color('--primary');
    const inicioColor = color('--badge-start');
    const pista = color('--divider');
    const borde = color('--stroke');
    const fondo = color('--surface-card');
    const texto = color('--text-main');
    const textoSuave = color('--text-sub');
    const r = this.radioHito;
    const g = this.grosor;

    ctx.font = fuente(600, 11.5);
    let tamImporte = 11.5;
    const izquierda = r + this.margenHalo / 2;
    const anchoUltimo = this.importes.length ? ctx.measureText(this.importes[this.importes.length - 1]).width : 0;
    const derecha = ancho - Math.max(r * 1.6, anchoUltimo / 2);
    const cy = this.margenHalo / 2 + r;
    const x = (v) => izquierda + ((v - this.inicio) / (this.fin - this.inicio)) * (derecha - izquierda);

    // Si dos importes vecinos no caben, se encogen lo justo.
    let factor = 1;
    for (let i = 1; i < this.hitos.length; i++) {
      const disponible = x(this.hitos[i]) - x(this.hitos[i - 1]) - 6;
      const necesario = (ctx.measureText(this.importes[i - 1]).width + ctx.measureText(this.importes[i]).width) / 2;
      factor = Math.min(factor, Math.min(1, disponible / necesario));
    }
    if (factor < 1) tamImporte = 11.5 * factor;

    // La pista y lo conseguido.
    ctx.fillStyle = css(pista);
    rectRedondo(ctx, izquierda, cy - g / 2, derecha - izquierda, g, g / 2);
    ctx.fill();
    const hasta = x(Math.min(this.fin, Math.max(this.inicio, this.mostrado)));
    if (hasta > izquierda) {
      ctx.save();
      const degradado = ctx.createLinearGradient(0, 0, ancho, 0);
      degradado.addColorStop(0, css(inicioColor));
      degradado.addColorStop(1, css(principal));
      ctx.fillStyle = degradado;
      ctx.shadowColor = css(conAlfa(principal, 90));
      ctx.shadowBlur = 7;
      ctx.shadowOffsetY = 2;
      rectRedondo(ctx, izquierda, cy - g / 2, hasta - izquierda, g, Math.min(g / 2, (hasta - izquierda) / 2));
      ctx.fill();
      ctx.restore();
      this.dibujarBrillo(ctx, izquierda, hasta, cy);
    }

    const alcance = (h) => Math.min(1, Math.max(0, (this.mostrado - h) / TRANSICION_HITO + 1));
    const siguiente = this.hitos.find((h) => alcance(h) < 1);
    ctx.font = fuente(600, 11);
    const mNum = metricas(ctx);
    const baseNumero = cy + (mNum.asc - mNum.desc) / 2;
    ctx.font = fuente(600, tamImporte);
    const mImp = metricas(ctx);
    const baseImporte = cy + r + SEPARACION_IMPORTE + mImp.asc;
    ctx.textAlign = 'center';
    let saltando = false;
    this.hitos.forEach((hito, i) => {
      const cx = x(hito);
      const lleno = alcance(hito);
      const esSiguiente = hito === siguiente;
      let escala = 1;
      const salto = this.saltos.get(hito);
      if (salto) {
        const t = (ahora - salto[0]) / DURACION_SALTO_MS;
        if (t < 1) {
          saltando = true;
          escala = 1 + (salto[1] ? 0.28 : -0.18) * Math.sin(Math.PI * t);
        }
      }
      const radio = r * escala;
      if (esSiguiente) this.dibujarLatido(ctx, cx, cy, principal);
      if (lleno < 1) {
        ctx.fillStyle = css(fondo);
        ctx.beginPath();
        ctx.arc(cx, cy, radio, 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = css(esSiguiente ? principal : borde);
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(cx, cy, radio - 1, 0, Math.PI * 2);
        ctx.stroke();
      }
      if (lleno > 0) {
        ctx.fillStyle = css(conAlfa(principal, 40 * lleno));
        ctx.beginPath();
        ctx.arc(cx, cy, radio + 4, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = css(conAlfa(principal, 255 * lleno));
        ctx.beginPath();
        ctx.arc(cx, cy, radio, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.font = fuente(600, 11);
      ctx.fillStyle = css(mezclar(esSiguiente ? principal : textoSuave, fondo, lleno));
      ctx.fillText(String(hito), cx, baseNumero);
      ctx.font = fuente(600, tamImporte);
      ctx.fillStyle = css(mezclar(esSiguiente ? texto : textoSuave, principal, lleno));
      ctx.fillText(this.importes[i], cx, baseImporte);
    });

    // El tirador en la punta del relleno, salvo encima de un hito.
    if (hasta > izquierda + g && !this.hitos.some((h) => Math.abs(x(h) - hasta) < r)) {
      ctx.save();
      ctx.shadowColor = 'rgba(0,0,0,0.235)';
      ctx.shadowBlur = 4.5;
      ctx.shadowOffsetY = 1;
      ctx.fillStyle = '#fff';
      ctx.beginPath();
      ctx.arc(hasta, cy, g * 0.85, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      ctx.fillStyle = css(principal);
      ctx.beginPath();
      ctx.arc(hasta, cy, g * 0.4, 0, Math.PI * 2);
      ctx.fill();
    }
    return saltando;
  }

  /** Un brillo blanco que recorre lo conseguido (primera mitad del efecto). */
  dibujarBrillo(ctx, izquierda, hasta, cy) {
    if (this.efecto == null) return;
    const t = this.efecto / FRACCION_BRILLO;
    if (t >= 1) return;
    const anchoBrillo = 48;
    const centro = izquierda - anchoBrillo + (hasta - izquierda + 2 * anchoBrillo) * t;
    const g = this.grosor;
    const d = ctx.createLinearGradient(centro - anchoBrillo / 2, 0, centro + anchoBrillo / 2, 0);
    d.addColorStop(0, 'rgba(255,255,255,0)');
    d.addColorStop(0.5, 'rgba(255,255,255,0.47)');
    d.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.save();
    rectRedondo(ctx, izquierda, cy - g / 2, hasta - izquierda, g, Math.min(g / 2, (hasta - izquierda) / 2));
    ctx.clip();
    ctx.fillStyle = d;
    ctx.fillRect(izquierda, cy - g / 2, hasta - izquierda, g);
    ctx.restore();
  }

  /** Dos ondas verdes que salen del siguiente hito y se apagan. */
  dibujarLatido(ctx, cx, cy, principal) {
    if (this.efecto == null) return;
    const onda = (this.efecto * LATIDOS) % 1;
    ctx.fillStyle = css(conAlfa(principal, (1 - onda) * 70));
    ctx.beginPath();
    ctx.arc(cx, cy, this.radioHito + this.margenHalo * 1.2 * onda, 0, Math.PI * 2);
    ctx.fill();
  }
}

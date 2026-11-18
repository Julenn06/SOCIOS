// AnimacionDescargaView.kt y EstallidoDescarga.kt: un anillo con degradado que avanza con un destello en la punta,
// un halo que respira y una flecha que baja; al terminar, el centro se rellena con un rebote, se dibuja la marca de
// verificación y salen ondas y partículas. Si falla, se vuelve rojo y tiembla.
import { color, conAlfa, css, fuente, mezclar, preparar } from '../lienzo.js';
import { activas, fastOutSlowIn, frenar, haptica, overshoot } from '../movimiento.js';

const VUELTA_RAPIDA_MS = 1100;
const VUELTA_LENTA_MS = 9000;
const SUAVIZADO_MS = 110;
const PULSO_MS = 1600;
const FLECHA_MS = 1150;
const DURACION_EXITO_MS = 1150;
const DURACION_ERROR_MS = 450;
const N_PARTICULAS = 16;

/** Números siempre iguales (semilla 7), para que el estallido sea el mismo cada vez. */
function azar(semilla) {
  let s = semilla >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

export class AnimacionDescarga {
  constructor(canvas) {
    this.canvas = canvas;
    this.fase = 'DESCARGANDO';
    this.objetivo = 0;
    this.mostrado = 0;
    this.completarAlLlegar = false;
    this.porcentajeAvisado = -1;
    this.reloj = 0;
    this.ultimo = 0;
    this.inicioFinal = 0;
    this.alCompletar = null;
    this.alTerminar = null;
    this.alMostrarProgreso = null;
    this.particulas = [];
    this.rebote = overshoot(1.4);
    this.frenada = frenar(1.6);
    this.id = 0;
    this.pedir();
  }

  mostrarProgreso(fraccion, inmediato = false) {
    if (this.fase !== 'DESCARGANDO') {
      this.fase = 'DESCARGANDO';
      this.mostrado = 0;
      this.completarAlLlegar = false;
      this.canvas.style.transform = '';
    }
    this.objetivo = fraccion;
    if (inmediato) this.mostrado = fraccion;
    this.pedir();
  }

  completar(animar) {
    if (this.fase === 'COMPLETANDO' || this.fase === 'COMPLETADA') return;
    this.objetivo = 1;
    if (!animar || !activas()) {
      this.fase = 'COMPLETADA';
      this.mostrado = 1;
      this.porcentajeAvisado = 100;
      this.alMostrarProgreso?.(1);
      this.alCompletar?.();
      this.alTerminar?.();
      this.pedir();
      return;
    }
    this.fase = 'DESCARGANDO';
    this.completarAlLlegar = true;
    this.pedir();
  }

  fallar(animar) {
    if (this.fase === 'FALLIDA') return;
    this.fase = 'FALLIDA';
    this.completarAlLlegar = false;
    const animado = animar && activas();
    this.inicioFinal = animado ? this.reloj : this.reloj - DURACION_ERROR_MS;
    if (animado) {
      const d = this.lado() * 0.05 * 0.9;
      this.canvas.animate(
        [0, -d, d, -d * 0.7, d * 0.7, -d * 0.35, 0].map((x) => ({ transform: `translateX(${x}px)` })),
        { duration: DURACION_ERROR_MS }
      );
      haptica.rechazar();
    }
    this.pedir();
  }

  lado() {
    return Math.min(this.canvas.clientWidth, this.canvas.clientHeight) || 208;
  }

  pedir() {
    if (this.id) return;
    this.id = requestAnimationFrame((t) => {
      this.id = 0;
      this.dibujar(t);
    });
  }

  detener() {
    cancelAnimationFrame(this.id);
    this.id = -1;
  }

  dibujar(ahora) {
    if (this.id === -1) return;
    const animado = activas();
    const dt = !animado || !this.ultimo ? 0 : Math.min(64, Math.max(0, ahora - this.ultimo));
    this.ultimo = ahora;
    this.reloj += dt;

    const w = this.canvas.clientWidth;
    const h = this.canvas.clientHeight;
    const ctx = preparar(this.canvas, w, h);
    const lado = Math.min(w, h);
    this.cx = w / 2;
    this.cy = h / 2;
    this.radio = lado * 0.28;
    this.grosor = lado * 0.05;
    this.c = {
      principal: color('--primary'),
      acento: color('--accent-blue'),
      error: color('--red-text'),
      pista: color('--divider'),
      numero: color('--text-main'),
      simbolo: color('--text-sub'),
      check: color('--on-primary'),
      ambar: color('--amber-text'),
      verdeSuave: color('--green-text-soft'),
      pendiente: color('--orange-pending')
    };

    if (this.fase === 'DESCARGANDO') {
      this.avanzar(dt, animado);
      if (this.completarAlLlegar && this.mostrado >= 1) this.empezarCelebracion();
    }
    if (this.fase === 'DESCARGANDO') this.dibujarDescarga(ctx, animado);
    else if (this.fase === 'COMPLETANDO') this.dibujarCelebracion(ctx, this.reloj - this.inicioFinal);
    else if (this.fase === 'COMPLETADA') this.dibujarCompletada(ctx);
    else this.dibujarFallo(ctx, this.reloj - this.inicioFinal);

    const seguir = this.fase === 'DESCARGANDO' ? animado || this.mostrado !== this.objetivo
      : this.fase === 'COMPLETANDO' ? true
        : this.fase === 'FALLIDA' ? this.reloj - this.inicioFinal < DURACION_ERROR_MS : false;
    if (seguir) this.pedir();
    else this.ultimo = 0;
  }

  avanzar(dt, animado) {
    const restante = this.objetivo - this.mostrado;
    if (!animado || restante <= 0) this.mostrado = this.objetivo;
    else {
      const paso = Math.min(restante, Math.min(dt / VUELTA_RAPIDA_MS, Math.max(Math.min(restante, dt / VUELTA_LENTA_MS), (restante * dt) / SUAVIZADO_MS)));
      this.mostrado += paso;
    }
    const porcentaje = Math.floor(this.mostrado * 100);
    if (porcentaje !== this.porcentajeAvisado) {
      this.porcentajeAvisado = porcentaje;
      queueMicrotask(() => this.alMostrarProgreso?.(this.mostrado));
    }
  }

  empezarCelebracion() {
    this.fase = 'COMPLETANDO';
    this.inicioFinal = this.reloj;
    this.completarAlLlegar = false;
    const r = azar(7);
    this.particulas = Array.from({ length: N_PARTICULAS }, (_, i) => ({
      angulo: (i * 2 * Math.PI) / N_PARTICULAS + r() * 0.4 - 0.2,
      distancia: this.radio * (0.3 + r() * 0.25),
      tamano: this.grosor * (0.26 + r() * 0.24),
      color: [this.c.principal, this.c.acento, this.c.ambar, this.c.verdeSuave, this.c.pendiente][i % 5],
      retraso: r() * 90
    }));
    haptica.confirmar();
    queueMicrotask(() => this.alCompletar?.());
  }

  arco(ctx, desde, barrido) {
    const { cx, cy, radio } = this;
    ctx.beginPath();
    ctx.arc(cx, cy, radio, (desde * Math.PI) / 180, ((desde + barrido) * Math.PI) / 180);
  }

  /** El trazo del anillo con su degradado (verde, azul, verde). */
  estiloArco(ctx) {
    const { cx, cy } = this;
    if (ctx.createConicGradient) {
      const g = ctx.createConicGradient(-Math.PI / 2, cx, cy);
      g.addColorStop(0, css(this.c.principal));
      g.addColorStop(0.5, css(this.c.acento));
      g.addColorStop(1, css(this.c.principal));
      return g;
    }
    return css(this.c.principal);
  }

  dibujarDescarga(ctx, animado) {
    const { cx, cy, radio, grosor } = this;
    const pulso = animado ? 0.5 + 0.5 * Math.sin((this.reloj / PULSO_MS) * 2 * Math.PI) : 0.5;
    const radioHalo = radio + grosor * 3;
    const escala = 1 + 0.06 * pulso;
    const halo = ctx.createRadialGradient(cx, cy, 0, cx, cy, radioHalo * escala);
    const a = (150 + 105 * pulso) / 255;
    halo.addColorStop(0, css(conAlfa(this.c.principal, 20 * a)));
    halo.addColorStop(radio / radioHalo, css(conAlfa(this.c.principal, 80 * a)));
    halo.addColorStop(1, css(conAlfa(this.c.principal, 0)));
    ctx.fillStyle = halo;
    ctx.beginPath();
    ctx.arc(cx, cy, radioHalo * escala, 0, Math.PI * 2);
    ctx.fill();

    ctx.lineWidth = grosor;
    ctx.strokeStyle = css(this.c.pista);
    ctx.beginPath();
    ctx.arc(cx, cy, radio, 0, Math.PI * 2);
    ctx.stroke();

    if (this.mostrado > 0) {
      const barrido = 360 * this.mostrado;
      ctx.strokeStyle = this.estiloArco(ctx);
      ctx.lineCap = 'round';
      this.arco(ctx, -90, barrido);
      ctx.stroke();
      this.dibujarDestello(ctx, -90 + barrido, animado);
    }
    this.dibujarFlecha(ctx, animado, 1);
    this.dibujarPorcentaje(ctx, 1);
  }

  dibujarDestello(ctx, angulo, animado) {
    const { cx, cy, radio, grosor } = this;
    const rad = (angulo * Math.PI) / 180;
    const x = cx + radio * Math.cos(rad);
    const y = cy + radio * Math.sin(rad);
    const c = this.mostrado < 0.5 ? mezclar(this.c.principal, this.c.acento, this.mostrado * 2) : mezclar(this.c.acento, this.c.principal, (this.mostrado - 0.5) * 2);
    const brillo = animado ? 0.85 + 0.15 * Math.sin(this.reloj / 140) : 1;
    for (const [alfa, r] of [[45, grosor * 1.25 * brillo], [90, grosor * 0.85]]) {
      ctx.fillStyle = css(conAlfa(c, alfa));
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.fillStyle = 'rgba(255,255,255,0.92)';
    ctx.beginPath();
    ctx.arc(x, y, grosor * 0.28 * brillo, 0, Math.PI * 2);
    ctx.fill();
  }

  dibujarFlecha(ctx, animado, opacidad) {
    const { cx, cy, radio, grosor } = this;
    const ciclo = animado ? (this.reloj % FLECHA_MS) / FLECHA_MS : 0.5;
    const desplazamiento = radio * (-0.09 + 0.18 * fastOutSlowIn(ciclo));
    const visibilidad = animado ? Math.sin(Math.PI * ciclo) : 1;
    const a = radio * 0.15;
    ctx.save();
    ctx.translate(cx, cy - radio * 0.34 + desplazamiento);
    ctx.strokeStyle = css(conAlfa(this.c.principal, 255 * opacidad * visibilidad));
    ctx.lineWidth = grosor * 0.55;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(0, -a);
    ctx.lineTo(0, a);
    ctx.moveTo(-a * 0.62, a * 0.38);
    ctx.lineTo(0, a);
    ctx.lineTo(a * 0.62, a * 0.38);
    ctx.stroke();
    ctx.restore();
  }

  dibujarPorcentaje(ctx, opacidad) {
    const { cx, cy, radio } = this;
    const numero = String(Math.floor(this.mostrado * 100));
    ctx.textAlign = 'left';
    ctx.font = fuente(600, radio * 0.44);
    const anchoNumero = ctx.measureText(numero).width;
    ctx.font = fuente(600, radio * 0.24);
    const anchoSimbolo = ctx.measureText('%').width;
    const separacion = radio * 0.04;
    const inicio = cx - (anchoNumero + separacion + anchoSimbolo) / 2;
    const base = cy + radio * 0.36;
    ctx.font = fuente(600, radio * 0.44);
    ctx.fillStyle = css(conAlfa(this.c.numero, 255 * opacidad));
    ctx.fillText(numero, inicio, base);
    ctx.font = fuente(600, radio * 0.24);
    ctx.fillStyle = css(conAlfa(this.c.simbolo, 255 * opacidad));
    ctx.fillText('%', inicio + anchoNumero + separacion, base);
  }

  radioRelleno() {
    return this.radio - this.grosor * 0.95;
  }

  dibujarCelebracion(ctx, t) {
    const { cx, cy, radio, grosor } = this;
    ctx.lineWidth = grosor;
    ctx.strokeStyle = this.estiloArco(ctx);
    ctx.beginPath();
    ctx.arc(cx, cy, radio, 0, Math.PI * 2);
    ctx.stroke();
    this.dibujarEstallido(ctx, t);
    const desvanecido = Math.min(1, Math.max(0, 1 - t / 160));
    if (desvanecido > 0) {
      this.dibujarFlecha(ctx, false, desvanecido);
      this.dibujarPorcentaje(ctx, desvanecido);
    }
    const relleno = this.rebote(Math.min(1, Math.max(0, t / 380)));
    ctx.fillStyle = css(this.c.principal);
    ctx.beginPath();
    ctx.arc(cx, cy, Math.max(0, this.radioRelleno() * relleno), 0, Math.PI * 2);
    ctx.fill();
    this.dibujarCheck(ctx, fastOutSlowIn(Math.min(1, Math.max(0, (t - 220) / 340))));
    if (t >= DURACION_EXITO_MS) {
      this.fase = 'COMPLETADA';
      queueMicrotask(() => this.alTerminar?.());
    }
  }

  dibujarEstallido(ctx, t) {
    const { cx, cy, radio, grosor } = this;
    for (let i = 0; i < 2; i++) {
      const p = Math.min(1, Math.max(0, (t - 120 - i * 220) / 700));
      if (p <= 0 || p >= 1) continue;
      const q = this.frenada(p);
      ctx.strokeStyle = css(conAlfa(this.c.principal, (1 - p) * 140));
      ctx.lineWidth = grosor * 0.5 * (1 - q) + 1;
      ctx.beginPath();
      ctx.arc(cx, cy, radio + grosor / 2 + radio * 0.55 * q, 0, Math.PI * 2);
      ctx.stroke();
    }
    for (const pt of this.particulas) {
      const p = Math.min(1, Math.max(0, (t - 140 - pt.retraso) / 760));
      if (p <= 0 || p >= 1) continue;
      const distancia = radio + grosor + pt.distancia * this.frenada(p);
      ctx.fillStyle = css(conAlfa(pt.color, (1 - p * p) * 255));
      ctx.beginPath();
      ctx.arc(cx + distancia * Math.cos(pt.angulo), cy + distancia * Math.sin(pt.angulo), pt.tamano * (1 - p * 0.7), 0, Math.PI * 2);
      ctx.fill();
    }
  }

  dibujarCompletada(ctx) {
    const { cx, cy, radio, grosor } = this;
    ctx.lineWidth = grosor;
    ctx.strokeStyle = this.estiloArco(ctx);
    ctx.beginPath();
    ctx.arc(cx, cy, radio, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fillStyle = css(this.c.principal);
    ctx.beginPath();
    ctx.arc(cx, cy, this.radioRelleno(), 0, Math.PI * 2);
    ctx.fill();
    this.dibujarCheck(ctx, 1);
  }

  dibujarCheck(ctx, fraccion) {
    if (fraccion <= 0) return;
    const { cx, cy, grosor } = this;
    const r = this.radioRelleno();
    const puntos = [[cx - r * 0.42, cy + r * 0.02], [cx - r * 0.12, cy + r * 0.32], [cx + r * 0.45, cy - r * 0.28]];
    const l1 = Math.hypot(puntos[1][0] - puntos[0][0], puntos[1][1] - puntos[0][1]);
    const l2 = Math.hypot(puntos[2][0] - puntos[1][0], puntos[2][1] - puntos[1][1]);
    let resto = (l1 + l2) * fraccion;
    ctx.strokeStyle = css(this.c.check);
    ctx.lineWidth = grosor * 0.95;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(...puntos[0]);
    const f1 = Math.min(1, resto / l1);
    ctx.lineTo(puntos[0][0] + (puntos[1][0] - puntos[0][0]) * f1, puntos[0][1] + (puntos[1][1] - puntos[0][1]) * f1);
    resto -= l1;
    if (resto > 0) {
      const f2 = Math.min(1, resto / l2);
      ctx.lineTo(puntos[1][0] + (puntos[2][0] - puntos[1][0]) * f2, puntos[1][1] + (puntos[2][1] - puntos[1][1]) * f2);
    }
    ctx.stroke();
  }

  dibujarFallo(ctx, t) {
    const { cx, cy, radio, grosor } = this;
    const entrada = this.rebote(Math.min(1, Math.max(0, t / 300)));
    ctx.lineWidth = grosor;
    ctx.lineCap = 'round';
    ctx.strokeStyle = css(conAlfa(this.c.error, 70));
    ctx.beginPath();
    ctx.arc(cx, cy, radio, 0, Math.PI * 2);
    ctx.stroke();
    if (this.mostrado > 0) {
      ctx.strokeStyle = css(this.c.error);
      this.arco(ctx, -90, 360 * this.mostrado);
      ctx.stroke();
    }
    ctx.fillStyle = css(conAlfa(this.c.error, 36));
    ctx.beginPath();
    ctx.arc(cx, cy, Math.max(0, this.radioRelleno() * entrada), 0, Math.PI * 2);
    ctx.fill();
    ctx.save();
    ctx.translate(cx, cy);
    ctx.scale(Math.max(0.001, entrada), Math.max(0.001, entrada));
    ctx.translate(-cx, -cy);
    ctx.strokeStyle = css(this.c.error);
    ctx.lineWidth = grosor * 0.8;
    ctx.beginPath();
    ctx.moveTo(cx, cy - radio * 0.34);
    ctx.lineTo(cx, cy + radio * 0.06);
    ctx.stroke();
    ctx.fillStyle = css(this.c.error);
    ctx.beginPath();
    ctx.arc(cx, cy + radio * 0.3, grosor * 0.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }
}

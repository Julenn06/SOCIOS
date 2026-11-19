// MuestraTemaView.kt: miniatura de la pantalla principal en un tema (claro, oscuro o, para "Sistema", partida en
// diagonal). Usa siempre los colores de ese tema, no los del que tenga la app.
import { preparar, rectRedondo, alCambiarTamano, medida } from '../lienzo.js';

const CLARO = { fondo: '#F2F4F0', tarjeta: '#FFFFFF', linea: '#CDD4CC', acento: '#0E7A55' };
const OSCURO = { fondo: '#0B100E', tarjeta: '#151C19', linea: '#33413A', acento: '#5CCB9E' };
const TEXTO_CABECERA = 'rgba(255,255,255,0.667)';

export class MuestraTema {
  constructor(canvas, tema) {
    this.canvas = canvas;
    this.tema = tema;
    alCambiarTamano(canvas, () => this.dibujar());
  }

  dibujar() {
    const w = medida(this.canvas).ancho;
    const h = medida(this.canvas).alto;
    if (!w) return;
    const ctx = preparar(this.canvas, w, h);
    ctx.save();
    rectRedondo(ctx, 0, 0, w, h, 10);
    ctx.clip();
    if (this.tema === 'CLARO') this.pantalla(ctx, w, h, CLARO);
    else if (this.tema === 'OSCURO') this.pantalla(ctx, w, h, OSCURO);
    else {
      this.pantalla(ctx, w, h, CLARO);
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(w, 0);
      ctx.lineTo(w, h);
      ctx.lineTo(0, h);
      ctx.closePath();
      ctx.clip();
      this.pantalla(ctx, w, h, OSCURO);
      ctx.restore();
    }
    ctx.restore();
  }

  /** Título, la cabecera verde, dos socios y el botón de añadir. */
  pantalla(ctx, w, h, p) {
    const margen = w * 0.09;
    const r = 4;
    const rr = (x0, y0, x1, y1, radio, relleno) => {
      ctx.fillStyle = relleno;
      rectRedondo(ctx, x0, y0, x1 - x0, y1 - y0, Math.min(radio, (x1 - x0) / 2, (y1 - y0) / 2));
      ctx.fill();
    };
    ctx.fillStyle = p.fondo;
    ctx.fillRect(0, 0, w, h);
    rr(margen, h * 0.07, w * 0.55, h * 0.115, r, p.linea);
    const g = ctx.createLinearGradient(0, h * 0.17, w, h * 0.42);
    g.addColorStop(0, '#155E46');
    g.addColorStop(1, '#0A2D22');
    rr(margen, h * 0.17, w - margen, h * 0.42, r * 1.5, g);
    rr(margen * 1.8, h * 0.23, w * 0.45, h * 0.26, r, TEXTO_CABECERA);
    rr(margen * 1.8, h * 0.3, w * 0.62, h * 0.36, r, TEXTO_CABECERA);
    const altoFila = h * 0.13;
    for (let i = 0; i < 2; i++) {
      const arriba = h * 0.48 + i * h * 0.17;
      rr(margen, arriba, w - margen, arriba + altoFila, r, p.tarjeta);
      ctx.fillStyle = p.acento;
      ctx.beginPath();
      ctx.arc(margen + altoFila / 2, arriba + altoFila / 2, altoFila * 0.22, 0, Math.PI * 2);
      ctx.fill();
      rr(margen + altoFila, arriba + altoFila * 0.4, w * (0.62 - i * 0.12), arriba + altoFila * 0.6, r, p.linea);
    }
    rr(w - margen - w * 0.26, h * 0.84, w - margen, h * 0.94, h, p.acento);
  }
}

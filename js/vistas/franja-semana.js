// FranjaSemanaView.kt: los cinco días laborables bajo la fecha del selector. En la semana actual, los días pasados en
// verde suave y hoy en verde, más largo; en las pasadas, todos en verde suave; en las futuras, todos vacíos.
import { color, conAlfa, css, mezclar, preparar, rectRedondo, alCambiarTamano } from '../lienzo.js';
import { activas, animarValor, LARGA_MS } from '../movimiento.js';

const DIAS = 5;
const ANCHO = 14, ANCHO_HOY = 24, ALTO = 4, ALTO_HOY = 5, SEPARACION = 5;

export class FranjaSemana {
  constructor(canvas) {
    this.canvas = canvas;
    this.llenos = 0;
    this.marcaHoy = 0;
    this.hoy = -1;
    this.animacion = null;
    canvas.style.width = `${ANCHO * (DIAS - 1) + ANCHO_HOY + SEPARACION * (DIAS - 1)}px`;
    canvas.style.maxWidth = '100%';
    canvas.style.height = `${ALTO_HOY}px`;
    alCambiarTamano(canvas, () => this.dibujar());
  }

  /** [momento]: 'PASADA', 'ACTUAL' o 'FUTURA'; [diaHoy] de 0 (lunes) a 6 (domingo). */
  mostrar(momento, diaHoy, animar) {
    const nuevoHoy = momento === 'ACTUAL' && diaHoy < DIAS ? diaHoy : -1;
    const objetivo = momento === 'PASADA' ? DIAS : momento === 'FUTURA' ? 0 : nuevoHoy >= 0 ? nuevoHoy : DIAS;
    const marca = nuevoHoy >= 0 ? 1 : 0;
    if (this.llenos === objetivo && this.marcaHoy === marca && (this.hoy === nuevoHoy || marca === 0)) return;
    this.animacion?.cancelar();
    if (!animar || !activas()) {
      this.llenos = objetivo;
      this.hoy = nuevoHoy;
      this.marcaHoy = marca;
      this.dibujar();
      return;
    }
    const llenosDesde = this.llenos;
    const marcaDesde = nuevoHoy < 0 || this.hoy === nuevoHoy ? this.marcaHoy : 0;
    if (nuevoHoy >= 0) this.hoy = nuevoHoy;
    this.animacion = animarValor(0, 1, LARGA_MS, (t) => {
      this.llenos = llenosDesde + (objetivo - llenosDesde) * t;
      this.marcaHoy = marcaDesde + (marca - marcaDesde) * t;
      if (t >= 1) this.hoy = nuevoHoy;
      this.dibujar();
    });
  }

  dibujar() {
    const w = this.canvas.clientWidth;
    const h = this.canvas.clientHeight;
    if (!w) return;
    const ctx = preparar(this.canvas, w, h);
    const principal = color('--primary');
    const lleno = conAlfa(principal, 120);
    const vacio = color('--divider');
    const anchos = [];
    for (let i = 0; i < DIAS; i++) anchos[i] = i === this.hoy ? ANCHO + (ANCHO_HOY - ANCHO) * this.marcaHoy : ANCHO;
    const suma = anchos.reduce((a, b) => a + b, 0);
    const escala = Math.min(1, w / (suma + SEPARACION * (DIAS - 1)));
    const hueco = SEPARACION * escala;
    for (let i = 0; i < DIAS; i++) anchos[i] *= escala;
    const total = anchos.reduce((a, b) => a + b, 0) + hueco * (DIAS - 1);
    let x = (w - total) / 2;
    const cy = h / 2;
    for (let i = 0; i < DIAS; i++) {
      const esHoy = i === this.hoy && this.marcaHoy > 0;
      const altoDia = esHoy ? ALTO + (ALTO_HOY - ALTO) * this.marcaHoy : ALTO;
      const relleno = Math.min(1, Math.max(0, this.llenos - i));
      const c = esHoy ? mezclar(mezclar(vacio, lleno, relleno), principal, this.marcaHoy) : mezclar(vacio, lleno, relleno);
      ctx.fillStyle = css(c);
      rectRedondo(ctx, x, cy - altoDia / 2, anchos[i], altoDia, altoDia / 2);
      ctx.fill();
      x += anchos[i] + hueco;
    }
  }
}

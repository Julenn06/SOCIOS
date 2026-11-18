// ListaSociosBottomSheetDialog.kt y util/BusquedaSocios.kt: los pendientes de semanas anteriores o la búsqueda en
// todas las semanas. Se pueden marcar, editar o borrar, y tocar uno abre su semana.
import * as estado from '../estado.js';
import { abrirHoja, cabeceraPanel } from '../paneles.js';
import { esc, parseImporte, TEXTOS } from '../formato.js';
import { crearCampo, html } from '../componentes.js';
import { ListaSocios, sinAgrupar } from '../filas.js';
import { abrirAccionesSocio, abrirFormularioSocio, confirmarEliminar } from './socio.js';

const normalizar = (t) => t.normalize('NFD').replace(/\p{Mn}+/gu, '').toLowerCase().replace(/\s+/g, ' ').trim();

/** Los socios con su texto ya preparado para buscar, del más reciente al más antiguo. */
function indexar(socios) {
  return socios
    .slice()
    .sort((a, b) => (a.semanaKey < b.semanaKey ? 1 : a.semanaKey > b.semanaKey ? -1 : a.dia - b.dia || b.timestamp - a.timestamp))
    .map((s) => [s, normalizar(`${s.nombreSocio} ${s.notas}`)]);
}

/** Los que contienen todas las palabras (sin tildes ni mayúsculas) o, si es un número, los de esa cuota. */
function filtrar(indice, texto) {
  const palabras = normalizar(texto).split(' ').filter(Boolean);
  if (!palabras.length) return [];
  const cuota = parseImporte(texto);
  return indice.filter(([s, donde]) => palabras.every((p) => donde.includes(p)) || (cuota != null && s.colaboracion === cuota)).map(([s]) => s);
}

/** [modo]: 'PENDIENTES' o 'BUSCAR'. */
export function abrirListaSocios(modo) {
  const buscando = modo === 'BUSCAR';
  return abrirHoja({
    tag: 'ListaSociosDialog',
    completa: true,
    clase: 'hoja-lista',
    construir: (panel) => {
      const s = panel.scroll;
      s.append(html(cabeceraPanel(buscando
        ? { placa: 'degradado-cabecera', iconoPlaca: 'search', titulo: 'Buscar socio', subtitulo: 'En todas tus semanas' }
        : { placa: 'degradado-ambar', iconoPlaca: 'pendientes', titulo: 'Pendientes anteriores', subtitulo: 'Socios sin conseguir de semanas pasadas' })));
      let campo = null;
      if (buscando) {
        campo = crearCampo({ etiqueta: 'Nombre, notas o cuota', iconoCampo: 'search', tipo: 'buscar', capitalizar: 'words', limpiar: true });
        campo.el.style.marginTop = '18px';
        campo.el.querySelector('.campo').style.borderRadius = '16px';
        s.append(campo.el);
      }
      const ayuda = html(`<div class="ayuda-lista">${buscando
        ? 'Escribe un nombre, una palabra de las notas o una cuota (por ejemplo, 20). Toca un resultado para ir a su semana.'
        : 'Márcalos como conseguidos con el interruptor, edítalos o bórralos. Toca uno para ir a su semana.'}</div>`);
      const resultados = html('<div class="resultados-lista oculto"></div>');
      const vacio = html(`<div class="vacio-lista oculto"><svg class="borde-discontinuo" aria-hidden="true"><rect x="0.75" y="0.75" rx="22" ry="22" style="width:calc(100% - 1.5px);height:calc(100% - 1.5px)" fill="none" stroke="var(--stroke)" stroke-width="1.5" stroke-dasharray="7 5"/></svg><span></span></div>`);
      const contenedor = html('<div class="lista-busqueda"></div>');
      s.append(ayuda, resultados, vacio, contenedor);

      let dialogoEliminar = null;
      const editar = (socio) => abrirFormularioSocio({ socio });
      const eliminar = (socio) => {
        if (dialogoEliminar) return;
        dialogoEliminar = confirmarEliminar(socio, () => estado.eliminarSocio(socio), () => (dialogoEliminar = null));
      };
      const lista = new ListaSocios(contenedor, {
        alCambiarHecho: (socio, hecho) => estado.setSocioHecho(socio.id, hecho),
        alEditar: editar,
        alEliminar: eliminar,
        alAcciones: (socio) => abrirAccionesSocio(socio, { alEditar: editar, alEliminar: eliminar }),
        mostrarSemana: true,
        alAbrir: (socio) => {
          estado.irASemana(socio.semanaKey);
          panel.cerrar();
        }
      });

      let indice = null;
      let sociosIndexados = null;
      const pintar = () => {
        let socios;
        const texto = campo ? campo.valor().trim() : '';
        if (buscando) {
          // El texto de cada socio se prepara una vez cuando cambian los socios, no con cada tecla.
          if (estado.revisionDatos() !== sociosIndexados) {
            sociosIndexados = estado.revisionDatos();
            indice = indexar(estado.todosLosSocios());
          }
          socios = filtrar(indice, texto);
        } else {
          socios = estado.pendientesAnteriores();
        }
        lista.mostrar(sinAgrupar(socios), 'normal');
        const hayConsulta = !buscando || texto !== '';
        resultados.classList.toggle('oculto', !(buscando && socios.length));
        resultados.textContent = TEXTOS.resultados(socios.length);
        ayuda.classList.toggle('oculto', !(socios.length === 0 || !buscando));
        vacio.classList.toggle('oculto', !(hayConsulta && socios.length === 0));
        vacio.querySelector('span').innerHTML = buscando ? `Ningún socio coincide con «${esc(texto)}»` : 'No te queda ningún pendiente de semanas anteriores';
      };
      campo?.input.addEventListener('input', pintar);
      campo?.input.addEventListener('keydown', (e) => e.key === 'Enter' && campo.input.blur());
      pintar();
      if (campo) setTimeout(() => campo.foco(), 320);
      const dejar = estado.suscribir(pintar);
      panel.el.querySelector('[data-cerrar]').addEventListener('click', () => panel.cancelar());
      return () => {
        dejar();
        dialogoEliminar?.cerrar();
      };
    }
  });
}

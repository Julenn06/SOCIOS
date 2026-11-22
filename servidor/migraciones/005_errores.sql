-- Errores de la web en los dispositivos (fallos de JavaScript y lo que bloquea la política de seguridad), para
-- enterarse de lo que falla en un móvil concreto. Sin cuenta, IP ni datos de la app: tipo, mensaje (sin textos entre
-- comillas), archivo y línea, versión y navegador. Los iguales se cuentan en una fila (firma); quedan los 500 últimos.

CREATE TABLE errores (
  firma CHAR(32) NOT NULL PRIMARY KEY,
  tipo VARCHAR(12) NOT NULL,
  mensaje VARCHAR(300) NOT NULL,
  fuente VARCHAR(200) NOT NULL,
  version VARCHAR(20) NOT NULL,
  navegador VARCHAR(80) NOT NULL,
  veces INT NOT NULL DEFAULT 1,
  primero BIGINT NOT NULL,
  ultimo BIGINT NOT NULL,
  KEY errores_ultimo (ultimo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

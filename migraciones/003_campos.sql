-- Sincronización campo a campo (ver sincronizacion.js): la hora de cada campo de cada registro, en JSON
-- ({ "nombreSocio": 1763800000000, "borrado": 1763700000000, … }). Las filas de antes quedan con NULL: todos sus campos
-- cuentan como cambiados en `modificado`.

ALTER TABLE socios ADD COLUMN campos TEXT NULL;
ALTER TABLE bonosRegalo ADD COLUMN campos TEXT NULL;
ALTER TABLE cobros ADD COLUMN campos TEXT NULL;
ALTER TABLE ajustes ADD COLUMN campos TEXT NULL;

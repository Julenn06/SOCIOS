package com.julen.socios.data.backup

/** Marca de orden de bytes que algunos editores (Excel, Bloc de notas) añaden al guardar en UTF-8. */
internal val BOM_UTF8: String = Char(0xFEFF).toString()

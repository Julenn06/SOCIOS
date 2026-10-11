package com.julen.socios.data.local

import androidx.room.withTransaction

/** Ejecuta un bloque de escrituras de forma atómica. Separado de Room para poder sustituirlo en tests. */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}

class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction(block)
}

package com.weback.robot.security

import com.weback.robot.domain.model.PersistedSessionRecord

sealed interface TokenSaveResult {
    data object Success : TokenSaveResult
    data class Failure(val safeCategory: String) : TokenSaveResult
}

sealed interface TokenLoadResult {
    data object Empty : TokenLoadResult
    data class Loaded(val record: PersistedSessionRecord) : TokenLoadResult
    data class CorruptedOrInvalidated(val safeCategory: String) : TokenLoadResult
}

sealed interface TokenClearResult {
    data object Success : TokenClearResult
    data class Failure(val safeCategory: String) : TokenClearResult
}

interface TokenStore {
    suspend fun save(record: PersistedSessionRecord): TokenSaveResult
    suspend fun load(): TokenLoadResult
    suspend fun clear(): TokenClearResult
}

package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.LocalCodecError

/**
 * Result returned by [HoslamStreamFramer.finish].
 */
sealed interface HoslamFinishResult {
    data class EndOfStream(
        val framesDelivered: Long,
        val bytesConsumed: Long
    ) : HoslamFinishResult

    data class Failed(
        val error: LocalCodecError
    ) : HoslamFinishResult
}

package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrame
import com.weback.robot.domain.map.LocalCodecError

/**
 * Result returned by [HoslamFrameDecoder.decode].
 */
sealed interface HoslamDecodeResult {
    data class Decoded(
        val frame: HoslamFrame
    ) : HoslamDecodeResult

    data class Failed(
        val error: LocalCodecError
    ) : HoslamDecodeResult
}

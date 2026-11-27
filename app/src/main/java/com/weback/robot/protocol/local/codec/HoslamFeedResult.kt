package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RawHoslamFrame

/**
 * Result returned by [HoslamStreamFramer.feed].
 */
sealed interface HoslamFeedResult {
    data class Frames(
        val frames: List<RawHoslamFrame>
    ) : HoslamFeedResult

    data class Failed(
        val validFramesBeforeFailure: List<RawHoslamFrame>,
        val error: LocalCodecError
    ) : HoslamFeedResult
}

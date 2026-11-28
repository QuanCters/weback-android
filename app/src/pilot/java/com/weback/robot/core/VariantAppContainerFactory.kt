package com.weback.robot.core

import android.content.Context

internal object VariantAppContainerFactory {
    fun create(context: Context): AppContainer = DefaultPilotContainer(context = context)
}

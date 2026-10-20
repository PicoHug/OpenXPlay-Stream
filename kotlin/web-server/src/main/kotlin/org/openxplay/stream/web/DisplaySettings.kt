// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

package org.openxplay.stream.web

import org.openxplay.stream.core.Origin
import kotlinx.serialization.json.*

public data class DisplaySettings(public val resolution: String = "auto", public val screenWidth: Int = 200, public val videoCodec: String = "auto") {
    init { require(resolution in setOf("auto", "native", "1080p", "720p", "screen") && screenWidth in setOf(125, 200, 250, 300) && videoCodec in setOf("auto", "h265", "h264")) }
    public fun message(): JsonObject = buildJsonObject {
        put("type", "display-settings"); put("resolution", resolution); put("screenWidth", screenWidth); put("videoCodec", videoCodec)
    }
}
/** Callbacks run after publishing the new current value; closing removes the listener. */
public interface DisplaySettingsPort {
    public fun current(): DisplaySettings
    public fun set(settings: DisplaySettings, origin: Origin)
    public fun listen(callback: (DisplaySettings) -> Unit): AutoCloseable
}

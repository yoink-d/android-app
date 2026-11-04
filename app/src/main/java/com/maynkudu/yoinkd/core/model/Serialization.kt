package com.maynkudu.yoinkd.core.model

import kotlinx.serialization.json.Json

val mediaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

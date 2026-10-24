package com.shilapi.xcertplay.airplay

/** First local teardown trigger. These fixed labels contain no peer data or exception text. */
internal enum class AirPlayCloseSource {
    OWNER_CLOSED,
    CONTROL_EOF,
    CONTROL_IO,
    CONTROL_DECRYPT,
    EVENT_EOF,
    EVENT_IO,
    EVENT_DECRYPT,
    EVENT_SETUP_FAILED,
    SCREEN_CLOSED,
    IAP_FAILED,
    RENDERER_FAILED,
}

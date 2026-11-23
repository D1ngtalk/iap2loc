# The app uses no reflection: kotlinx.serialization is only used through JsonElement, and the
# WebView talks HTTP to the in-process server instead of a JavaScript interface.
-dontwarn org.slf4j.**

# Third-party notices

VAP/VAPX format and rendering behavior are based on Tencent VAP v2. The earlier source-port implementation was replaced on 2026-11-10 by a shared Kotlin protocol/parser, playback clock and Compose Canvas compositor, with thin system decoder/frame adapters.

The repository no longer bundles Tencent's Android/iOS player engine, the Huawei/community C++/ArkTS player, nlohmann/json, or FFmpeg. Their historical license texts remain in `licenses/` for attribution and reference; retaining a combined upstream notice does not mean its listed components are linked into this library.

The library uses Compose Multiplatform, Kotlin coroutines, kotlinx.serialization and platform media/graphics APIs through their ordinary dependencies. Their licenses are supplied by their respective distributions. Library code is under the root MIT license.

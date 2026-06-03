# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),


# Changelog — ResQMesh AI

## [0.0.5] - 2026-06-02

### Added
- Isolation-gated supplementary Coded (S=8) advertising probe in `BluetoothGattServerManager` — when a node sees fewer than `ISOLATION_PEER_THRESHOLD` (2) active peers it additionally emits low-duty-cycle (~3 s on / ~42 s off) long-range Coded-PHY beacons to give distant or wall-separated BT5 peers an extra chance to discover and connect; stops automatically once the node is well-meshed, saving battery and freeing the 2.4 GHz channel for neighbours
- `BluetoothGattServerManager.updateMeshDensity(activePeerCount)` and `BluetoothConnectionManager.updateMeshDensity(...)` — feed the active-peer count from `onPeerListUpdated` down to the advertising layer so the probe is driven by a real isolation signal rather than a fixed timer

### Changed
- Coded-PHY advertising is now **additive, never substitutive**: the 1M legacy advertisement is always kept on air as a floor so BT4 devices can always discover the node. The supplementary S=8 probe is an independent advertising set layered on top, with its own callback, and never replaces the 1M beacon (closes a silent-partition risk where an S=8-only node was invisible to all BT4 scanners). The explicit range-test diagnostic path (user selects a non-1M codec) is unchanged and suppresses the probe so it does not interfere

### Fixed
- Coded probe degrades gracefully on hardware without Coded PHY / extended advertising: it is gated on `isLeCodedPhySupported`, `isLeExtendedAdvertisingSupported`, and `isMultipleAdvertisementSupported`, every controller call is wrapped in try/catch, and a runtime failure latches the probe off (`codedProbeUnsupported`) so an unsupported device never crashes or retry-spams the advertiser

## [0.0.4] - 2026-04-22

### Added
- `VisionTFLiteClassifier` (`:resqmesh-ai`, `ai/vision/`) — on-device TFLite image classifier for emergency scene detection (fire, flood, normal, security, weather); loads `emergency_vision_model.tflite` and `vision_label_map.json` from assets; accepts Bitmap input, resizes to 224×224, normalizes pixels to [-1, 1], and runs inference; returns `SceneAnalysisResult` compatible with the existing vision pipeline; includes `isAvailable(context)` check for graceful degradation
- `emergency_vision_model.tflite` (2.68 MB MobileNetV2) and `vision_label_map.json` assets in `:resqmesh-ai` module
- `PriorityQueueBenchmarkTest` (M5 benchmark suite) in `:resqmesh-ai` — JUnit coverage measuring CRITICAL-packet latency under queue load
- `LatencyBenchmark` utility in `:resqmesh-ai` for classifier/relay performance measurement

### Changed
- `ImageSceneAnalyzer` now uses a hybrid approach: tries `VisionTFLiteClassifier` first (confidence ≥ 0.55); falls back to ML Kit Image Labeling when TFLite is unavailable or not confident enough
- Added `noCompress += "tflite"` to `:resqmesh-ai` build config to prevent asset compression
- Retrained `emergency_model.tflite` — grew from 430 KB to 1.16 MB with updated vocabulary; `label_map.json` and `tokenizer.json` refreshed accordingly for improved accuracy across the 10 emergency categories
- `KeywordMessageClassifier` — additional phrases and edge-case handling refinements
- `ICS213ReportGenerator` output formatting refinements (header, signature blocks, row layout)
- `ICS213ReportScreen` layout cleanup — improved spacing, header rendering, and signature-block presentation
- `ChatScreen`, `EmergencyFeedSheet`, `InputComponents` — miscellaneous UI polish for message input, emergency feed presentation, and category badges
- `README.md` substantially rewritten (161 insertions / 90 deletions) to document the photo-to-category pipeline, retrained text classifier, and current feature set
- `CLAUDE.md` expanded with image-to-category pipeline documentation, vision module layout, and model asset inventory

### Fixed
- `LocationAttachSheet` — corrected dialog lifecycle so `onSkip` and swipe-dismiss correctly suppress the outgoing message; `onSend` reliably appends `📍 lat,lon` to the message body
- `ChatScreen` — location-dialog wiring updated to match the corrected `LocationAttachSheet` callbacks

## [0.0.3] - 2026-04-06

### Added
- `SceneToEmergencyMapper` (`:resqmesh-ai`, `ai/vision/`) — pure-Kotlin mapper that converts ML Kit image label strings to the app's 9 emergency categories (MEDICAL, FIRE, FLOOD, COLLAPSE, SECURITY, WEATHER, INFRASTRUCTURE, RESOURCE_REQUEST, MISSING_PERSON); produces a pre-formatted TextField description with emoji and actionable suffix (e.g. `"🔥 FIRE: Fire, smoke, burning. Requires immediate response."`)
- `ImageSceneAnalyzer` (`:app`, `features/media/`) — suspending wrapper around ML Kit Image Labeling (bundled model, works fully offline); reuses `ImageUtils.loadBitmapWithExifOrientation`; delegates label→category mapping to `SceneToEmergencyMapper`
- `PhotoReportButton` (`:app`, `ui/media/`) — new orange `AddAPhoto` icon button in the chat input row; single-click opens gallery, long-click opens camera (same gesture as the replaced `ImagePickerButton`); shows `CircularProgressIndicator` while ML Kit runs (~300 ms); three outcome paths: emergency category detected → `onSceneAnalyzed`, generic labels detected → `onSceneAnalyzed` with `"📷 Scene: …"` description, ML Kit hard failure → `onImageReady` fallback (plain image send)
- `pendingPhotoPath` state in `ChatScreen` and `MeshPeerListSheet` — stores the captured image path until the user validates the generated description and taps Send; photo and text message are dispatched together on confirmation

### Changed
- `ImagePickerButton` usage in `InputComponents.kt` commented out and replaced by `PhotoReportButton`; original code preserved for future reference
- `MessageInput`, `ChatInputSection` signatures — new `onSceneAnalyzed: (description, imagePath) -> Unit` parameter added; all call sites updated (`ChatScreen`, `MeshPeerListSheet`)
- `onSend` handler in `ChatScreen` and `MeshPeerListSheet` — flushes `pendingPhotoPath` via `sendImageNote` before dispatching the text message when a photo-report is in progress

## [0.0.2] - 2026-04-03

### Changed
- Retrained TFLite emergency classifier model (`emergency_model.tflite`) with updated vocabulary and softmax output layer — model now outputs probabilities directly, removing the need for manual softmax post-processing
- Added 10th output class `NONE` to `label_map.json` and `MessagePriority` enum (renamed from `LOW`) for non-emergency messages
- `ClassifierUtils.mapToPriority()` now maps unknown/unrecognised categories to `NONE` instead of `NORMAL`
- `TFLiteMessageClassifier` no longer applies `softmax()` to model output (model includes softmax in its final layer)

### Added
- 90 vocabulary coverage tests in `TFLiteMessageClassifierTest` — 10 example messages per emergency category, verifying the tokenizer recognises enough domain-specific words

### Fixed
- Removed leftover debug log in `TFLiteMessageClassifier.loadWordIndex()`

## [0.0.1] - 2026-03-09

### Added
- `:resqmesh-ai` Gradle library module — all AI/ML code extracted from `:app`, independently testable
- `KeywordMessageClassifier` — ~90 FEMA/ICS keyword rules across 9 emergency categories (MEDICAL, FIRE, FLOOD, COLLAPSE, SECURITY, WEATHER, MISSING_PERSON, INFRASTRUCTURE, RESOURCE_REQUEST)
- `TFLiteMessageClassifier` — on-device neural classifier; auto-activates when `message_classifier.tflite` asset is present
- `CompositeMessageClassifier` — keyword-first pipeline with TFLite fallback; keyword results bypass confidence threshold for CRITICAL/HIGH priority
- Coloured left stripe and emoji badge on every classified message (e.g. `🏥 MEDICAL · 94%`); all colours derived from `MaterialTheme.colorScheme` for light/dark theme support
- `EmergencyFeedSheet` — categories sorted by priority with coloured stripes, message counts, and a close button
- `CategoryMessagesScreen` — full-screen slide-in view filtered to one emergency category; system back button supported via `BackHandler`
- Offline speech-to-text (`VoskTranscribeButton`) integrated into the chat input bar
- Emergency Feed 🚨 button moved to the header, right of the peer counter; shows live emergency message count (`👥 2  🚨 3`) for ambient situational awareness
- Tap any message in `CategoryMessagesScreen` to dismiss the overlay and jump to that message in the main chat
- `ICS213ReportData` + `ICS213ReportGenerator` — FEMA ICS-213 General Message form rendered as self-contained HTML; pure Kotlin, no Android deps, unit-testable in `:resqmesh-ai`
- `ICS213PrintHelper` — loads report into a headless `WebView` and triggers Android `PrintManager` (Save as PDF / physical printer)
- `ICS213ReportScreen` — full-screen Compose preview with black/green header and Share button; white background for print legibility
- "GENERATE ICS-213 REPORT" button pinned at the bottom of `EmergencyFeedSheet`; hidden when the Feed is empty
- `EmergencyClassification.kt` (`:resqmesh-ai`) — canonical `shouldShowEmergencyBadge()` and `shouldShowPossibleBadge()` predicates; `categoryEmojiAndLabel()` extracted from `:app` into the AI module
- `LocationAttachSheet` — optional GPS / manual address attachment for CRITICAL and HIGH messages; appended as `📍 lat,lon` to message body; location never pre-filled
- **BLE Priority Queue** — `BluetoothPacketBroadcaster` actor replaced with `java.util.PriorityQueue` + `Mutex` + `CONFLATED` signal; CRITICAL packets preempt all queued NORMAL/LOW packets at the radio layer
- `RoutedPacket.priority: Int` field (default 2 = NORMAL); `sendMessage()` classifies content with `KeywordMessageClassifier` synchronously and sets priority before enqueuing
- `PriorityQueueBenchmarkTest` — 5 JUnit tests proving CRITICAL latency improvement; benchmark output: 1001× faster delivery vs FIFO with 1 000 queued packets at 100 µs/packet
- **AI Energy Management (Feature 6 — first iteration)** — relay probability now adapts to the device's battery state without Wi-Fi Direct or protocol changes:
  - `EnergyMode` enum in `:resqmesh-ai` (PERFORMANCE / BALANCED / POWER_SAVER / ULTRA_LOW_POWER) mirrors `PowerManager.PowerMode` without creating a cross-module Android dependency
  - `EnergyRelayPolicy` (`:resqmesh-ai`) — pure Kotlin policy engine combining `networkFactor(networkSize)` × `energyMultiplier(EnergyMode)` for normal packets; separate `criticalRelayProbability()` for high-TTL SOS/MAYDAY packets that always relay (0.20 at ULTRA_LOW_POWER as last-resort forwarder)
  - `PacketRelayManager.energyMode` — new field; `shouldRelayPacket()` now delegates entirely to `EnergyRelayPolicy`; debug info includes current energy mode and both relay probabilities
  - `PacketProcessor.energyMode` — delegating property forwarding to `PacketRelayManager`
  - `BluetoothConnectionManager.onEnergyModeChanged` — nullable callback invoked on every `PowerManager` mode change; maps `PowerMode → EnergyMode` via private extension without coupling `:resqmesh-ai` to Android types
  - `BluetoothMeshService.setupDelegates()` — wires the callback: `connectionManager.onEnergyModeChanged = { mode -> packetProcessor.energyMode = mode }`
  - `EnergyRelayPolicyTest` — 21 JUnit tests covering all boundary values, all enum combinations, passive-mode invariant (ULTRA_LOW_POWER → 0.0 normal / 0.2 critical), and monotonicity of both axes
  - `BluetoothConnectionManager.onPowerModeChanged()` now shows a short `Toast` on the main thread whenever the power mode changes automatically; message uses 4 string resources (`power_mode_performance`, `power_mode_balanced`, `power_mode_power_saver`, `power_mode_ultra_low_power`); uses `Handler(Looper.getMainLooper())` to marshal from the IO coroutine scope

### Fixed
- `ICS213ReportScreen`: WebView dependency removed from the report preview entirely; the form is now rendered as a pure Compose `LazyColumn` directly from `ICS213ReportData` (white background, monospace font, priority badges, signature blocks); this eliminates the Android 11 Trichrome crash where `Package not found: com.google.android.webview` prevented the report from showing at all
- `ICS213ReportScreen` print/share button: tries `ICS213PrintHelper` (WebView + PrintManager) first; if WebView throws (broken Trichrome), falls back to writing the HTML to the app cache and sharing it via `FileProvider` so the user can open and print it in any browser; `ICS213PrintHelper.printReport()` now lets the exception propagate so the caller can handle the fallback
- `CompositeMessageClassifier`: TFLite results with confidence below `EMERGENCY_CONFIDENCE_THRESHOLD` (0.25f) were still showing emergency badges because `mapToPriority("MEDICAL"/"COLLAPSE")` returns `CRITICAL`, bypassing the confidence gate in `shouldShowEmergencyBadge()`; TFLite priority is now capped to `NORMAL` when confidence < threshold so only keyword-matched results keep their CRITICAL/HIGH bypass
- `KeywordMessageClassifier`: messages like "I can't move" (injury/entrapment) did not trigger the `LocationAttachSheet` because no matching CRITICAL keyword existed; added entrapment/mobility-loss phrases to `CRITICAL_KEYWORDS`: `CAN'T MOVE`, `CANT MOVE`, `CANNOT MOVE`, `CAN NOT MOVE` (→ MEDICAL), `TRAPPED UNDER`, `I AM TRAPPED`, `I'M TRAPPED`, `IM TRAPPED` (→ COLLAPSE), `TRAPPED INSIDE` (→ MEDICAL)
- ICS-213 report: date field rendered as `2026--0-3-` due to double-formatting; now uses `yyyy-MM-dd` directly
- ICS-213 report: same-sender messages within 5-minute window were merged into one row; each incident now renders as a separate row
- ICS-213 report: GPS coordinates truncated mid-number when original text exceeded 80 chars; location part (`\n📍 …`) is now split out before truncation and preserved in full
- ICS-213 report: time ranges (`17:49:12–17:49:32`) shown for consolidated entries; removed with per-message rows
- `CategoryMessagesScreen` filter missed `shouldShowEmergencyBadge` check, showing low-confidence messages in category view
- `"COLLAPSED"` keyword mapped to COLLAPSE (structural); reclassified as MEDICAL since "person collapsed" is the dominant context; structural collapse still caught by `"BUILDING COLLAPSED"` and `"STRUCTURE COLLAPSE"`
- `CompositeMessageClassifier` now skips messages shorter than 3 words (e.g. `"."`, `"hey"`) to prevent false-positive badge display; standalone `KeywordMessageClassifier` (used for priority-queue urgency) is unaffected so `"SOS"` still receives CRITICAL priority
- `ClassifierUtils.mapToPriority` returned `LOW` for unknown TFLite categories; corrected to `NORMAL`
- PRIVACY_POLICY.md updated to document voluntary emergency location sharing added in this release

----------------------------------------------------------
----------------------------------------------------------

## Upstream BitChat history (archived)
## [1.4.0] - 2025-10-15
### Fixed
- fix: Resolve debug settings bottom sheet crash on some devices (Issue #472)
  - Fixed IllegalFormatConversionException in DebugSettingsSheet.kt when scrolling through debug settings
  - Corrected string formatting for debug_target_fpr_fmt and debug_derived_p_fmt string resources
  - Improved string resource parameter handling for numeric values

## [0.7.2] - 2025-07-20
### Fixed
- fix: battery optimization screen content scrollable with fixed buttons

## [0.7.1] - 2025-07-19

### Added
- feat(battery): add battery optimization management for background reliability

### Fixed
- fix: center align toolbar item in ChatHeader - passed modifier.fillmaxHeight so the content inside the row can actually be centered
- fix: update sidebar text to use string resources
- fix(chat): cursor location and enhance message input with slash command styling

### Changed
- refactor: remove context attribute at ChatViewModel.kt
- Refactor: Migrate MainViewModel to use StateFlow

### Improved
- Use HorizontalDivider instead of deprecated Divider
- Use contentPadding instead of padding so items remain fully visible


and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7]

### Added
- Location services check during app startup with educational UI
- Message text selection functionality in chat interface
- Enhanced RSSI tracking and unread message indicators
- Major Bluetooth connection architecture refactoring with dedicated managers

### Fixed
- **Critical**: Android-iOS message fragmentation compatibility issues
  - Fixed fragment size (500→150 bytes) and ID generation for cross-platform messaging
  - Ensures Android can properly communicate with iOS devices
- DirectMessage notifications and text copying functionality
- Smart routing optimizations (no relay loops, targeted delivery)
- Build system compilation issues and null pointer exceptions

### Changed
- Comprehensive dependency updates (AGP 8.10.1, Kotlin 2.2.0, Compose 2025.06.01)
- Optimized BLE scan intervals for better battery performance
- Reduced excessive logging output

### Improved
- Cross-platform compatibility with iOS and Rust implementations
- Connection stability through architectural improvements
- Battery performance via scan duty cycling
- User onboarding with location services education

## [0.6]

### Added
- Channel password management with `/pass` command for channel owners
- Monochrome/themed launcher icon for Android 12+ dynamic theming support
- Unit tests package with initial testing infrastructure
- Production build optimization with code minification and shrinking
- Native back gesture/button handling for all app views

### Fixed
- Favorite peer functionality completely restored and improved
  - Enhanced favorite system with fallback mechanism for peers without key exchange
  - Fixed UI state updates for favorite stars in both header and sidebar
  - Improved favorite persistence across app sessions
- `/w` command now displays user nicknames instead of peer IDs
- Button styling and layout improvements across the app
  - Enhanced back button positioning and styling
  - Improved private chat and channel header button layouts
  - Fixed button padding and alignment issues
- Color scheme consistency updates
  - Updated orange color throughout the app to match iOS version
  - Consistent color usage for private messages and UI elements
- App startup reliability improvements
  - Better initialization sequence handling
  - Fixed null pointer exceptions during startup
  - Enhanced error handling and logging
- Input field styling and behavior improvements
- Sidebar user interaction enhancements
- Permission explanation screen layout fixes with proper vertical padding

### Changed
- Updated GitHub organization references in project files
- Improved README documentation with updated clone URLs
- Enhanced logging throughout the application for better debugging

## [0.5.1] - 2025-07-10

### Added
- Bluetooth startup check with user prompt to enable Bluetooth if disabled

### Fixed
- Improved Bluetooth initialization reliability on first app launch

## [0.5] - 2025-07-10

### Added
- New user onboarding screen with permission explanations
- Educational content explaining why each permission is required
- Privacy assurance messaging (no tracking, no servers, local-only data)

### Fixed
- Comprehensive permission validation - ensures all required permissions are granted
- Proper Bluetooth stack initialization on first app load
- Eliminated need for manual app restart after installation
- Enhanced permission request coordination and error handling

### Changed
- Improved first-time user experience with guided setup flow

## [0.4] - 2025-07-10

### Added
- Push notifications for direct messages
- Enhanced notification system with proper click handling and grouping

### Improved
- Direct message (DM) view with better user interface
- Enhanced private messaging experience

### Known Issues
- Favorite peer functionality currently broken

## [0.3] - 2025-07-09

### Added
- Battery-aware scanning policies for improved power management
- Dynamic scan behavior based on device battery state

### Fixed
- Android-to-Android Bluetooth Low Energy connections
- Peer discovery reliability between Android devices
- Connection stability improvements

## [0.2] - 2025-07-09

### Added
- Initial Android implementation of bitchat protocol
- Bluetooth Low Energy mesh networking
- End-to-end encryption for private messages
- Channel-based messaging with password protection
- Store-and-forward message delivery
- IRC-style commands (/msg, /join, /clear, etc.)
- RSSI-based signal quality indicators

### Fixed
- Various Bluetooth handling improvements
- User interface refinements
- Connection reliability enhancements

## [0.1] - 2025-07-08

### Added
- Initial release of bitchat Android client
- Basic mesh networking functionality
- Core messaging features
- Protocol compatibility with iOS bitchat client

[Unreleased]: https://github.com/permissionlesstech/bitchat-android/compare/0.5.1...HEAD
[0.5.1]: https://github.com/permissionlesstech/bitchat-android/compare/0.5...0.5.1
[0.5]: https://github.com/permissionlesstech/bitchat-android/compare/0.4...0.5
[0.4]: https://github.com/permissionlesstech/bitchat-android/compare/0.3...0.4
[0.3]: https://github.com/permissionlesstech/bitchat-android/compare/0.2...0.3
[0.2]: https://github.com/permissionlesstech/bitchat-android/compare/0.1...0.2
[0.1]: https://github.com/permissionlesstech/bitchat-android/releases/tag/0.1

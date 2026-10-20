# Gemini Live Mode - Design (optional, additive, Gemini-only)

Status: draft. Builds only after the main app is stable (bug sweep done,
history hygiene merged, UI polish landed).

## Goal

An optional "Live" mode where the user talks to zhypix in real time
(voice in / voice out) while the agent keeps full screen control
(tap / type / scroll) and sees the screen as a live frame stream.
Strictly additive: the existing REST multi-provider loop stays the
default; Live is a Gemini-only session the user can start/stop.

## Why a separate mode

- Live uses the bidirectional WebSocket API (BidiGenerateContent), not
  generateContent. It does not support automatic tool-response handling
  and has its own message formats, so it cannot ride on the REST path.
- Live context is small (~60k). The 60k working budget (deferred from
  the main loop) applies HERE, not to the REST loop.

## API facts (verified against ai.google.dev Live API docs, 2026-10)

- WebSocket session; setup message carries model, systemInstruction,
  tools (functionDeclarations), responseModalities (AUDIO and/or TEXT).
- Models: Gemini Live preview models (e.g. the current `gemini-*-live`
  preview ids) - resolved at runtime from the provider model list, never
  hardcoded (repo rule).
- Function calling: supported. Tool calls arrive as `toolCall` server
  messages; the client MUST answer manually with `toolResponse`
  (functionResponses keyed by call id). Unlike REST, no automatic
  handling.
- Video input: individual image frames sent as `realtimeInput.video`
  with base64 JPEG and mimeType "image/jpeg". Practical rate ~1 fps;
  frames are downscaled (long side <= 768-1024px) to save bandwidth
  and context.
- Audio in: 16-bit PCM, 16kHz, realtimeInput.audio. Audio out: 24kHz
  PCM chunks in server content; played as they arrive.
- Interruption: user barge-in is native (server VAD); playback must be
  cancellable on `interrupted` server messages.
- Session limits: sessions are time-limited (~10 min with audio, or
  ~15 min with context compression / session resumption handles). Plan
  automatic resume via session resumption tokens.

## Architecture (reuses the existing execution layer)

New package `com.example.live`:

- `LiveSessionManager` - owns the OkHttp WebSocket, JSON protocol
  (setup, realtimeInput, clientContent, toolResponse), reconnect and
  resumption. Started/stopped from the floating bubble and the mic
  button; one active session at a time.
- `LiveAudioPipeline` - AudioRecord (16kHz PCM) -> websocket;
  websocket PCM chunks -> AudioTrack. Mute/unmute, barge-in cancel.
- `LiveFrameStreamer` - every ~1s (or on screen change hash), grab the
  current screenshot via the existing
  `ZhypixAccessibilityService.takeScreenshotWithHash` path, reuse its
  scale/quality settings, send as video frame. Skips when the hash is
  unchanged.
- `LiveToolBridge` - maps Live function calls to the EXISTING
  `ActionExecutor` / `GestureMapper.executeAction` (click, type, swipe,
  global actions, intents) and wraps results as Live toolResponses.
  The sensitive-action confirm gate and touch-to-pause from the REST
  loop apply unchanged.
- Context budget: reuse `utils/ContextBudget` ideas under a 60k clamp
  inside Live mode only (compact tree text, single latest frame, short
  running log injected via clientContent text when needed).

## UI integration (minimal, after the UI agent's polish lands)

- Live button on the floating bubble + a mode toggle in settings.
  Hand off to the UI agent for look; this work provides
  `LiveSessionManager.state: StateFlow` (IDLE / CONNECTING / LISTENING /
  SPEAKING / ACTING / ERROR) for them to render.
- Uses device locale for transcription display; no hardcoded model names
  (Live model id comes from the fetched provider model list filtered by
  the live prefix, with a graceful "Live not available" empty state).

## Risks / open questions

- Session length limits -> implement resumption tokens early.
- Rate limits on realtime input -> frame cadence adaptive (pause when
  screen hash unchanged, drop frames while a tool action runs).
- Cost: audio+video streaming burns tokens fast; default OFF, explicit
  user start, visible "Live" indicator, auto-stop on bubble close.

## Build order

1. LiveSessionManager + echo text-only session (no audio/video).
2. LiveToolBridge + screen control through Live (text-in, tools out).
3. LiveFrameStreamer (vision).
4. LiveAudioPipeline (voice in/out, barge-in).
5. Resumption, budget guard, polish, screenshots.

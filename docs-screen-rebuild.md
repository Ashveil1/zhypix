# Screen-control rebuild

The original screen-control system and its prompts were deleted before this replacement. This is an independent implementation of the supplied architecture study's design ideas. It does not call AGI services or use their code, assets, keys or prompts.

## Stated design values

| Setting | Value | Study section |
|---|---:|---|
| Maximum steps | 150 | 4.5, 5 |
| Stage retry delay | 1000 ms | 5.4 |
| Billing retry delay (not used without AGI billing) | 1500 ms | 5.4 |
| Tap duration | 125 ms | 7.2 |
| Double tap gap | 175 ms | 7.3 |
| Hold | Android long-press timeout + 100 ms | 7.3 |
| Default swipe | 860 ms | 7.3 |
| TalkBack tap starts | 0, 475, 675 ms | 7.3 |
| TalkBack tap duration | 75 ms | 7.3 |
| Capture queue slots | 3 | 8.1 |
| Agent / bubble image long side | 780 / 540 px | 8.1 |
| JPEG quality | 75 | 8.1 |

These are scheduling inputs, not guarantees about elapsed time. Android and display refresh cannot promise 0.01 ms execution accuracy. Some study values were themselves approximate, not precise measurements.

## Explicit local defaults where the study does not specify a value

- One retry per capture/model/parse stage, 1000 ms before the retry.
- Action retry only on definite pre-dispatch rejection. A cancellation or timeout could already have affected the screen, so it is not automatically repeated.
- Post-action delay: 1000 ms (local default, tuned from the emulator live run: with 0 ms the next capture was taken before the page transition and the model repeated its tap). A fresh capture is still required before every decision.
- Gesture timeout: 5000 ms. Capture timeout: 15000 ms.
- Android screenshot minimum interval: 350 ms, to avoid OS throttling.
- Overlay compositor settling: 32 ms.
- Visible-node budget: 400, maximum depth 20, label cap 200 characters.
- Maximum wait action: 60 seconds. Optional gesture duration range: 1..60000 ms.
- Model HTTP call timeout: 90 seconds; last 16 action results are included in each request.
- Coordinates use raw screen pixels. Both image and screen dimensions are supplied. No client coordinate guessing or jitter.

## Differences and remaining work

Working core: parser and terminal taxonomy; fresh-observation loop; cancellation; stage retries; screenshot/phone-state; gestures; typing; app launch; user's Gemini/OpenAI-compatible provider.

Safety differences: password fields are handed to the user; clipboard is never used for a text fallback; uncertain gestures are not blindly retried. Confirmation is required before sensitive effects.

Landed since (unit tested in JVM CI; not exercised live on a device):
- Memory: remember, keyword recall, hard delete (`recall_memory`, `forget_memory` tools). JSON file store, separate from Room.
- Needs-you: tasks that stop for confirmation/login/captcha are saved and survive restart. No UI lists them yet.
- Routines: interval (min 15 min) or daily schedules, saved with `schedule_routine`/`list_routines`/`delete_routine`. A minute ticker in the app process runs one due routine at a time. They only run while the app process is alive and accessibility is enabled. There is no background alarm, so a killed app misses runs (at most one catch-up run later).

Verified live on an emulator with a free model (not a physical phone): single and two-level Settings navigation, and retry after a swallowed tap. The app's own gesture adapter has not been exercised end to end.

Not yet claimed as complete: live voice tool bridge on a device, guides recording, notification listener, background virtual display, remote control. Remote control would require a separate trusted backend and is not implemented. Billing and AGI account gating are not used.

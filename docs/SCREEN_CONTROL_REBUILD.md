# Screen-Control Rebuild (plan item 1)

Replaces the old screen-control path: screenshot-on-a-timer polling in
`AgentViewModel` + scattered gesture helpers. Design follows the public
architecture of modern Android agents (accessibility-tree-first, vision as
fallback, verify every step).

## New pipeline (per step)

1. PERCEIVE: `Perception.snapshot()` returns a compact accessibility tree
   (ElementResolver compact format) + app/window metadata. Screenshot only
   when the model has vision AND the tree is insufficient (web content,
   canvas/games, image-only screens) or the model asks.
2. SETTLE: `Perception.awaitQuiet()` waits for the AccessibilityEvent stream
   to go quiet (120ms quiet window, caps: 900ms normal / 2500ms app launch).
   No fixed sleeps.
3. ACT: `ActionExecutor` runs one action:
   - click: ElementResolver -> ACTION_CLICK -> climb to clickable ancestor
     -> gesture at center with jitter bounded inside the element.
   - type: focused editable node first, ACTION_SET_TEXT, clipboard paste
     only into the focused node.
   - scroll: ACTION_SCROLL_* on a scrollable node, swipe fallback.
   - global: BACK/HOME/RECENTS/NOTIFICATIONS; open_app via launch intent.
4. VERIFY: tree diff before/after (LocalPostActionVerifier), escalate to the
   planner after 2 no-change repeats. No LLM round-trip needed for verify.
5. STOP: step budget, loop detection, touch-to-pause (user touching the
   screen pauses the agent instantly), task-complete signal from the model.

## Migration steps

- [x] CI added (PR #1) so every push compiles + tests.
- [x] Cleanup pass: dead code removed, event-callback throttled, inputText
      mispaste fixed (branch screen-control-rebuild).
- [ ] New `com.example.agent` package: Perception + ActionExecutor, built
      against ZhypixAccessibilityService's improved primitives.
- [ ] AgentViewModel's computer_use_action handler routes through
      ActionExecutor; old paths (screenshot-interval loop, fixed delays,
      duplicate helpers) deleted.
- [ ] Touch-to-pause: any user touch event pauses the run (TYPE_VIEW_HOVER /
      gesture detection heuristics), resume via bubble.
- [ ] Numbers: log per-step latency (settle ms, action ms, verify ms) so we
      can report real before/after speed.

## Not in scope

- AGI Inc's APK / model. Everything here is standard public Android
  accessibility technique.

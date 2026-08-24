# Design: ย้าย Terminal ไปใช้ Termux TerminalView (PTY จริง)

- **วันที่:** 2026-08-24
- **สถานะ:** Approved (design level)
- **ขอบเขต:** แทนที่ terminal UI แบบ line-based ใน `TerminalScreen` ด้วย `com.termux.view.TerminalView` + `terminal-emulator` จาก termux-app เพื่อให้ได้ interactive shell ผ่าน PTY จริง (vim, htop, TUI, ANSI colors, cursor control)

## 1. พื้นฐานปัจจุบัน (Current State)

- `LinuxTerminalSimulator` (object, ~3.7k บรรทัด) บริหาร distros/sessions และรันคำสั่งผ่าน `ProcessBuilder` + PRoot — **one-shot per command**, output เก็บเป็น `terminalLines` (line-based)
- `TerminalScreen.kt` (~1.9k บรรทัด) render `terminalLines` ด้วย Compose + session tabs + distro installer + X11 desktop viewer
- ไม่มี PTY, ไม่มี persistent shell process, โปรแกรม TUI (vim/htop) ใช้ไม่ได้
- Native binaries (`jniLibs/*.so`) = Termux binaries แปลงชื่อ (proot/busybox/talloc/android-shmem) — ต้องการ `useLegacyPackaging` + `extractNativeLibs` ตาม `PROOT_DEVELOPER_GUIDE.md`

## 2. Approach ที่เลือก

**Vendor source** จาก termux-app tag **`v0.118.0`** (Apache-2.0) เป็น Gradle submodules:

```
settings.gradle.kts
  include(":terminal-emulator")   // VT100/xterm engine (Java) + JNI .c (createSubprocess/PTY/setWindowSize)
  include(":terminal-view")       // com.termux.view.TerminalView
:app → implementation(project(":terminal-view"))
```

- คง `LICENSE` + `NOTICE` ของ termux-app ไว้ในแต่ละ module
- เพิ่ม `externalNativeBuild { cmake }` ใน `:terminal-emulator`; ABI filters `{arm64-v8a, x86_64}` ต้องตรงกับ `:app`
- **Prerequisite:** NDK + CMake ติดตั้งผ่าน SDK Manager

ถูกเลือกเหนือ JitPack dependency (artifact ไม่ถูกดูแล, คุม build ไม่ได้) และเหนือการ render เองด้วย Compose (งานเยอะกว่า, fidelity ต่ำกว่า)

## 3. Architecture

### 3.1 Units ใหม่ใน `:app`

| Unit | ความรับผิดชอบ |
|---|---|
| `ZhypixTerminalManager` | Wrap `TerminalSession`: spawn proot login เข้า distro / busybox `ash` ถ้าไม่มี distro; จัดการ lifecycle, cwd tracking, restart |
| `TerminalScreen` (rewrite) | `AndroidView { TerminalView }` + extra-keys row + tabs + drawer (distro installer, X11 viewer, copy/paste, font size, kill session) |
| `buildProotCommand(distro, cwd)` | Refactor ตรรกะสร้าง `prootCmdList` จาก `LinuxTerminalSimulator` ให้เป็น function เดียวใช้ร่วมกันทั้ง executeCommand path และ PTY session path |

### 3.2 Data Flow

```
keystroke → TerminalView → TerminalSession.write() → PTY fd
  → bash/proot → JNI reader thread → TerminalEmulator.parseAndAppend()
  → SessionChangedCallback.onTextChanged() → TerminalView.invalidate()
```

### 3.3 สิ่งที่ไม่แตะ

- `LinuxTerminalSimulator.executeCommand()` — AgentViewModel / GestureMapper / FileUtils ใช้อยู่ ทำงานต่อเหมือนเดิม (spawn process แยก)
- Public API: `currentDirectory`, `sessions`, `isInstalled`, `installedDistros`, X11 functions, distro install/remove/login
- `terminalLines` — คงไว้ถ้ามี consumer นอก screen เก่า, ลบได้ถ้าไม่มี (ตัดสินตอน implement)

### 3.4 UI (TerminalScreen ใหม่)

- Full-screen `TerminalView`, ธีมดำ Termux default color scheme, font = system monospace (custom TTF picker = YAGNI)
- Extra-keys row (Compose): ESC / CTRL / TAB / arrows / ฯลฯ ส่ง bytes เข้า `session.write()`
- Top bar: tabs (new/rename/close/switch) สไตล์เดียวกับ TermuxActivity
- Drawer/menu: **Distro installer (ฟีเจอร์เดิมต้องอยู่ครบ)**, X11 desktop toggle+viewer, copy/paste, font size +/-, wake lock toggle, kill session
- Long-press บน view → context menu (paste/select)

## 4. Error Handling

| กรณี | พฤติกรรม |
|---|---|
| Process exit | `onSessionFinished` → banner "Process exited (code)" + ปุ่ม restart |
| Binary พัง (`Exec format error`) | reuse `isValidElf()` เช็คก่อน spawn, error message ชี้ไป `PROOT_DEVELOPER_GUIDE.md` |
| JNI read thread crash / fd ปิด | manager remove session + switch tab อัตโนมัติ |

## 5. Testing

1. **JVM unit tests** กับ `TerminalEmulator` โดยตรง (pure Java): escape sequences (color, cursor move, resize) → assert buffer
2. **Robolectric smoke test**: compose `TerminalScreen` ใหม่, ยืนยัน TerminalView/tabs มีใน tree
3. **Manual verify** บน emulator x86_64: `vim`, `htop`, `ls --color`, pinch-zoom, long-press paste

## 6. Risks

- NDK build เพิ่ม compile time และต้องมี toolchain ครบ
- ABI ทั้ง app และ terminal-emulator .so ต้อง sync กัน ({arm64-v8a, x86_64})
- PRoot ภายใต้ PTY = pattern เดียวกับ proot-distro ของ Termux (พิสูจน์แล้วว่าทำงานได้) แต่ env vars ที่จำเป็น (PROOT_LOADER, PROOT_TMP_DIR, LANG/LC_ALL, resolv.conf repair) ต้อง set ครบตามที่ guide ระบุ

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.utils.LinuxTerminalSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies the PTY launch-spec contract shared by the Termux TerminalView integration.
 * Binaries are faked with valid ELF headers so no real proot/busybox is needed on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PtyLaunchSpecTest {

  private fun fakeElf(path: String, context: Context) {
    val f = File(context.filesDir, path)
    f.parentFile?.mkdirs()
    val header = ByteArray(64)
    header[0] = 0x7f.toByte()
    header[1] = 'E'.code.toByte()
    header[2] = 'L'.code.toByte()
    header[3] = 'F'.code.toByte()
    header[20] = 1
    val abis = android.os.Build.SUPPORTED_ABIS
    val primaryAbi = if (abis.isNotEmpty()) abis[0] else ""
    val isArm = primaryAbi.contains("arm") || primaryAbi.contains("aarch64")
    val eMachine = if (isArm) 0xB7 else 0x3E
    header[18] = (eMachine and 0xFF).toByte()
    header[19] = ((eMachine shr 8) and 0xFF).toByte()
    f.writeBytes(header)
  }

  @Test
  fun `host shell spec uses system sh with terminal env`() {
    // Host PTY launches /system/bin/sh directly (SELinux W^X blocks exec of
    // app-private busybox on API 29+; see PROOT_DEVELOPER_GUIDE 7.6). On the
    // JVM test host /system/bin/sh does not exist, so it falls back to "sh".
    val context = ApplicationProvider.getApplicationContext<Context>()
    LinuxTerminalSimulator.initialize(context)

    val spec = LinuxTerminalSimulator.buildPtyLaunchSpec(null)

    assertNotNull(spec)
    spec!!
    assertTrue(
      "unexpected host shell: ${spec.executablePath}",
      spec.executablePath == "/system/bin/sh" || spec.executablePath == "sh"
    )
    assertEquals(listOf(spec.executablePath), spec.args)
    assertTrue(spec.env.any { it == "TERM=xterm-256color" })
    assertNull(spec.distroAlias)
  }

  @Test
  fun `distro spec launches guest shell through proot`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    fakeElf("lib/libproot.so", context)
    fakeElf("proot-distros/test/bin/bash", context)
    LinuxTerminalSimulator.initialize(context)
    LinuxTerminalSimulator.ensureProotPathResolved(context)

    val spec = LinuxTerminalSimulator.buildPtyLaunchSpec("test", "/root")

    assertNotNull(spec)
    spec!!
    assertEquals("test", spec.distroAlias)
    assertTrue(spec.args.contains("/bin/sh"))
    assertTrue(spec.args.last().contains("exec /bin/bash"))
    // host-side env must carry the loader path contract for proot
    assertTrue(spec.env.any { it.startsWith("PROOT_TMP_DIR=") })
  }

  @Test
  fun `missing distro returns null`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    LinuxTerminalSimulator.initialize(context)

    assertNull(LinuxTerminalSimulator.buildPtyLaunchSpec("not-installed"))
  }
}

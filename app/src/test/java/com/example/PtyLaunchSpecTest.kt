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
@Config(sdk = [36])
class PtyLaunchSpecTest {

  private fun fakeElf(path: String, context: Context) {
    val f = File(context.filesDir, path)
    f.parentFile?.mkdirs()
    f.writeBytes(byteArrayOf(0x7f) + "ELF".toByteArray() + ByteArray(16))
  }

  @Test
  fun `host shell spec uses busybox sh with terminal env`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    fakeElf("lib/libbusybox.so", context)
    LinuxTerminalSimulator.initialize(context)

    val spec = LinuxTerminalSimulator.buildPtyLaunchSpec(null)

    assertNotNull(spec)
    spec!!
    assertEquals(listOf("sh"), spec.args)
    assertEquals(File(context.filesDir, "lib/libbusybox.so").absolutePath, spec.executablePath)
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

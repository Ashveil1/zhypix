package com.example

import com.example.utils.LinuxTerminalSimulator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathGuardTest {

    @Test
    fun entriesInsideRootAreAccepted() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhypix-guard-${System.nanoTime()}").apply { mkdirs() }
        try {
            assertTrue(LinuxTerminalSimulator.isPathInside(File(root, "usr/bin/sh"), root))
            assertTrue(LinuxTerminalSimulator.isPathInside(File(root, "a/b/c/../../c/x"), root))
            assertTrue(LinuxTerminalSimulator.isPathInside(root, root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun traversalOutsideRootIsRejected() {
        val root = File(System.getProperty("java.io.tmpdir"), "zhypix-guard-${System.nanoTime()}").apply { mkdirs() }
        try {
            assertFalse(LinuxTerminalSimulator.isPathInside(File(root, "../../etc/passwd"), root))
            assertFalse(LinuxTerminalSimulator.isPathInside(File(root, "../sibling/x"), root))
        } finally {
            root.deleteRecursively()
        }
    }
}

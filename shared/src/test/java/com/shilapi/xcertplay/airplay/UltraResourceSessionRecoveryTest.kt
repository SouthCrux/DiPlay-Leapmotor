package com.shilapi.xcertplay.airplay

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraResourceSessionRecoveryTest {
    private fun withParent(block: (File) -> Unit) {
        val parent = Files.createTempDirectory("ultra-recovery-test").toFile()
        try { block(parent) } finally { parent.deleteRecursively() }
    }

    private fun python(script: String, path: File): Process {
        val executable = File("/usr/bin/python3")
        assumeTrue("POSIX cross-process lock probe requires system Python", executable.canExecute())
        return ProcessBuilder(executable.path, "-u", "-c", script, path.path).redirectErrorStream(true).start()
    }

    @Test fun recoveryRemovesAbandonedCopiesWithoutTouchingActiveSessionsOrOtherDirectories() = withParent { parent ->
        val stale = File(parent, "ultra-session-abandoned").apply { mkdir() }
        File(stale, ".owner.lock").writeBytes(byteArrayOf())
        File(stale, "old.png").writeBytes(byteArrayOf(7))
        val incomplete = File(parent, "ultra-session-incomplete").apply { mkdir() }
        File(incomplete, "partial.lua").writeText("partial")
        val unrelated = File(parent, "other-app-data").apply { mkdir() }
        val first = UltraResourceSessionLease.acquire(parent)
        try {
            val active = File(first.directory, "active.png").apply { writeBytes(byteArrayOf(9)) }
            UltraResourceSessionLease.acquire(parent).use {
                assertFalse(stale.exists())
                assertFalse(incomplete.exists())
                assertTrue(unrelated.isDirectory)
                assertArrayEquals(byteArrayOf(9), active.readBytes())
            }
            assertTrue(first.directory.isDirectory)
        } finally { first.close() }
        assertFalse(first.directory.exists())
    }

    @Test fun recoveryDoesNotReleaseAnExistingSameProcessLeaseWhenInspectingOtherSessions() = withParent { parent ->
        UltraResourceSessionLease.acquire(parent).use { first ->
            UltraResourceSessionLease.acquire(parent).use {
                val probe = python("""
                    import fcntl, sys
                    with open(sys.argv[1], 'r+b') as stream:
                        try:
                            fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                            print('unexpectedly-unlocked')
                        except BlockingIOError:
                            print('busy')
                """.trimIndent(), File(first.directory, ".owner.lock"))
                try {
                    assertTrue(probe.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(0, probe.exitValue())
                    assertEquals("busy", probe.inputStream.bufferedReader().readText().trim())
                } finally { probe.destroyForcibly() }
            }
        }
    }

    @Test fun aDifferentProcessOwnsItsCopyUntilItsProcessDies() = withParent { parent ->
        val external = File(parent, "ultra-session-external").apply { mkdir() }
        val owner = File(external, ".owner.lock").apply { writeBytes(byteArrayOf()) }
        File(external, "source.lua").writeText("return true")
        val process = python("""
            import fcntl, sys
            with open(sys.argv[1], 'r+b') as stream:
                fcntl.lockf(stream, fcntl.LOCK_EX)
                print('ready', flush=True)
                sys.stdin.buffer.read(1)
        """.trimIndent(), owner)
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            UltraResourceSessionLease.acquire(parent).use { active ->
                assertTrue(external.isDirectory)
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                UltraResourceSessionLease.acquire(parent).use {
                    assertFalse(external.exists())
                    assertTrue(active.directory.isDirectory)
                }
            }
        } finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
    }

    @Test fun abandonedTreeCleanupNeverFollowsSymbolicLinks() = withParent { parent ->
        val outside = Files.createTempDirectory("ultra-recovery-outside").toFile()
        try {
            val kept = File(outside, "keep.lua").apply { writeText("keep") }
            val stale = File(parent, "ultra-session-links").apply { mkdir() }
            Files.createSymbolicLink(File(stale, "linked").toPath(), outside.toPath())
            val rootLink = File(parent, "ultra-session-rootlink")
            Files.createSymbolicLink(rootLink.toPath(), outside.toPath())
            UltraResourceSessionLease.acquire(parent).use {
                assertFalse(stale.exists())
                assertTrue(Files.isSymbolicLink(rootLink.toPath()))
                assertEquals("keep", kept.readText())
            }
        } finally { outside.deleteRecursively() }
    }
}

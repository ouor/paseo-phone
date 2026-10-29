package io.github.ouor.paseophone.runtime.setup

import io.github.ouor.paseophone.runtime.linux.TestTar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SetupRunnerTest {
  private fun tmp() = Files.createTempDirectory("setup-test").toFile()

  private fun archives(dir: File): Pair<File, File> {
    val rootfs = TestTar().apply {
      entry("debian-trixie-aarch64/", '5')
      entry("debian-trixie-aarch64/etc/os-release", '0', "ID=debian\n".toByteArray())
    }.writeTo(File(dir, "rootfs.tar"))
    val node = TestTar().apply {
      entry("node-v22.20.0-linux-arm64/bin/node", '0', "ELF".toByteArray(), mode = "755".toInt(8))
    }.writeTo(File(dir, "node.tar"))
    return rootfs to node
  }

  @Test fun stepsFrom() {
    assertEquals(listOf("download", "extract", "configure", "packages", "paseo"), SetupRunner.stepsFrom(SetupMode.INSTALL, null))
    assertEquals(listOf("packages", "paseo"), SetupRunner.stepsFrom(SetupMode.INSTALL, "packages"))
    assertEquals(listOf("download", "extract", "paseo"), SetupRunner.stepsFrom(SetupMode.UPDATE_NODE, null))
    assertEquals(listOf("paseo"), SetupRunner.stepsFrom(SetupMode.UPDATE_DAEMON, null))
    assertNull(SetupRunner.stepsFrom(SetupMode.INSTALL, "start"))
    assertNull(SetupRunner.stepsFrom(SetupMode.UPDATE_DAEMON, "download"))
  }

  @Test fun extractUsesStagingThenRenames() {
    val dir = tmp()
    val (rootfsTar, nodeTar) = archives(dir)
    val staging = File(dir, "rootfs.staging")
    val rootfs = File(dir, "rootfs")
    File(staging, "leftover").apply { parentFile.mkdirs() }.writeText("from a killed extract")

    SetupRunner.extractInstall(rootfsTar, nodeTar, staging, rootfs) {}

    assertFalse(staging.exists())
    assertEquals("ID=debian\n", File(rootfs, "etc/os-release").readText())
    assertEquals("ELF", File(rootfs, "usr/local/bin/node").readText())
    assertFalse(File(rootfs, "leftover").exists())
    assertTrue(File(rootfs, "opt/paseo-phone").isDirectory)
  }

  @Test fun extractRefusesExistingRootfs() {
    val dir = tmp()
    val (rootfsTar, nodeTar) = archives(dir)
    val rootfs = File(dir, "rootfs")
    File(rootfs, "root/repo.txt").apply { parentFile.mkdirs() }.writeText("user work")
    try {
      SetupRunner.extractInstall(rootfsTar, nodeTar, File(dir, "rootfs.staging"), rootfs) {}
      fail("extract must refuse an existing rootfs")
    } catch (e: StepFailure) {
      assertEquals("unknown", e.code)
      assertEquals("rootfs exists", e.message)
    }
    assertEquals("user work", File(rootfs, "root/repo.txt").readText())
  }

  @Test fun nodeUpdateKeepsGlobalPackages() {
    val dir = tmp()
    val (_, nodeTar) = archives(dir)
    val rootfs = File(dir, "rootfs")
    File(rootfs, "usr/local/lib/node_modules/@getpaseo/cli/package.json").apply { parentFile.mkdirs() }.writeText("{}")
    File(rootfs, "usr/local/bin/node").apply { parentFile.mkdirs() }.writeText("old")
    SetupRunner.extractNode(nodeTar, rootfs) {}
    assertEquals("ELF", File(rootfs, "usr/local/bin/node").readText())
    assertTrue(File(rootfs, "usr/local/lib/node_modules/@getpaseo/cli/package.json").isFile)
  }

  @Test fun nodeVersionHeader() {
    val h = "#define NODE_MAJOR_VERSION 22\n#define NODE_MINOR_VERSION 20\n#define NODE_PATCH_VERSION 0\n"
    assertEquals("22.20.0", SetupRunner.parseNodeVersionHeader(h))
    assertNull(SetupRunner.parseNodeVersionHeader("nothing"))
  }
}

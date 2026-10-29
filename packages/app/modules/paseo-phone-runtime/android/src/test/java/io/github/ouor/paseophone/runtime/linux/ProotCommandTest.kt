package io.github.ouor.paseophone.runtime.linux

import io.github.ouor.paseophone.runtime.setup.SetupMode
import io.github.ouor.paseophone.runtime.setup.SetupRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandTest {
  private val root = File("/data/user/0/app/files/phone-runtime")
  private val lib = "/data/app/x/lib/arm64"

  private fun build(spec: ProotSpec, fake: List<Pair<File, String>> = emptyList()) =
    ProotCommand.build(lib, root, "poco-f3", fake, spec)

  @Test fun argvMatchesArchitecture() {
    val inv = build(
      ProotSpec(listOf("/bin/bash", "/opt/paseo-phone/run-daemon.sh"), env = mapOf("NODE_VERSION" to "22.20.0")),
      listOf(File(root, "sysdata/loadavg") to "/proc/loadavg"),
    )
    val a = inv.argv
    assertEquals("$lib/libproot.so", a[0])
    assertEquals(listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "-L"), a.subList(1, 5))
    assertTrue(a[5].startsWith("--kernel-release=\\Linux\\poco-f3\\6.17.0-PRoot\\#1 SMP"))
    assertTrue(a[5].endsWith("\\aarch64\\localdomain\\-1\\"))
    assertTrue("--change-id=0:0" in a)
    assertTrue("--rootfs=${File(root, "rootfs").path}" in a)
    assertTrue("--cwd=/root" in a)
    assertTrue("--bind=/dev/urandom:/dev/random" in a)
    assertTrue("--bind=${File(root, "sysdata/empty").path}:/sys/fs/selinux" in a)
    assertTrue("--bind=${File(root, "sysdata/loadavg").path}:/proc/loadavg" in a)
    assertTrue("--bind=${File(root, "tmp").path}:/dev/shm" in a)
    assertFalse(a.any { it.endsWith(":/root/exchange") })

    // guest part: env -i, defaults, spec env, then the command last
    val envAt = a.indexOf("/usr/bin/env")
    assertEquals("-i", a[envAt + 1])
    assertTrue(a.indexOf("--bind=/dev") < envAt)
    assertTrue("HOME=/root" in a && "PATH=${ProotCommand.GUEST_PATH}" in a && "NODE_VERSION=22.20.0" in a)
    assertEquals(listOf("/bin/bash", "/opt/paseo-phone/run-daemon.sh"), a.takeLast(2))

    assertEquals("$lib/libproot-loader.so", inv.env["PROOT_LOADER"])
    assertEquals(root.path, inv.env["LD_LIBRARY_PATH"])
    assertEquals(File(root, "tmp").path, inv.env["PROOT_TMP_DIR"])
  }

  @Test fun exchangeBindAndSpecEnvOverride() {
    val a = build(ProotSpec(listOf("true"), env = mapOf("TERM" to "dumb"), exchangeDir = File("/storage/emulated/0/Paseo"), cwd = "/tmp")).argv
    assertTrue("--bind=${File("/storage/emulated/0/Paseo").path}:/root/exchange" in a)
    assertTrue("--cwd=/tmp" in a)
    assertTrue("TERM=dumb" in a)
    assertFalse("TERM=xterm-256color" in a)
  }

  @Test fun nodename() {
    assertEquals("poco-f3", ProotCommand.nodename("POCO F3"))
    assertEquals("m2012k11ag", ProotCommand.nodename("M2012K11AG"))
    assertEquals("pixel-8-pro", ProotCommand.nodename("  Pixel 8 Pro!"))
    assertEquals("android", ProotCommand.nodename(""))
  }

  @Test fun fakeStatAgreesWithUptime() {
    val stat = FakeSysdata.stat(listOf(0, 1, 2, 4))
    val lines = stat.lines()
    assertTrue(lines[0].startsWith("cpu  "))
    assertEquals(listOf("cpu0", "cpu1", "cpu2", "cpu4"), lines.subList(1, 5).map { it.substringBefore(' ') })
    val idle = lines[0].removePrefix("cpu  ").split(' ')[3].toLong()
    assertEquals("124.08 %d.%02d\n".format(idle / 100, idle % 100), FakeSysdata.uptime(listOf(0, 1, 2, 4)))
    assertEquals(listOf(0, 1, 3), FakeSysdata.hostCpuIds("processor\t: 0\nprocessor : 1\nfoo: x\nprocessor : 3\n"))
  }

  @Test fun nodeShasums() {
    val body = "abc  node-v22.20.0-linux-x64.tar.xz\n" +
      "${"a".repeat(64)}  node-v22.20.0-linux-arm64.tar.xz\n"
    assertEquals("a".repeat(64), NodeDist.sha256From(body, NodeDist.archiveName("22.20.0")))
    assertNull(NodeDist.sha256From(body, "node-v22.20.0-linux-arm64.tar.gz"))
  }

  @Test fun exitInfoSignal() {
    assertEquals(ExitInfo(137, 9), ExitInfo.fromExitCode(137))
    assertEquals(ExitInfo(1, null), ExitInfo.fromExitCode(1))
  }

  @Test fun setupStepsAndNodeHeader() {
    assertEquals(listOf("paseo"), SetupRunner.stepsFor(SetupMode.UPDATE_DAEMON))
    assertEquals(listOf("download", "extract", "paseo"), SetupRunner.stepsFor(SetupMode.UPDATE_NODE))
    val h = "#define NODE_MAJOR_VERSION 22\n#define NODE_MINOR_VERSION 20\n#define NODE_PATCH_VERSION 0\n"
    assertEquals("22.20.0", SetupRunner.parseNodeVersionHeader(h))
    assertNull(SetupRunner.parseNodeVersionHeader("nothing"))
  }
}

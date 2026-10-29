package io.github.ouor.paseophone.runtime.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class TarExtractorTest {
  private fun extract(tar: TestTar, strip: Int): Pair<File, Int> {
    val dir = Files.createTempDirectory("tar-test").toFile()
    val n = TarExtractor.extractTar(ByteArrayInputStream(tar.bytes()), dir, strip)
    return dir to n
  }

  @Test fun extractsFilesAndDirsWithStrip() {
    val tar = TestTar()
    tar.entry("top/", '5', mode = "755".toInt(8))
    tar.entry("top/etc/", '5')
    tar.entry("top/etc/hostname", '0', "phone\n".toByteArray())
    tar.entry("top/bin/tool", '0', "#!/bin/sh\n".toByteArray(), mode = "755".toInt(8))
    val (dir, n) = extract(tar, 1)
    assertEquals(3, n) // "top/" itself is stripped away
    assertEquals("phone\n", File(dir, "etc/hostname").readText())
    assertTrue(File(dir, "bin/tool").isFile)
    assertFalse(File(dir, "top").exists())
  }

  @Test fun paxPathAndGnuLongNameWin() {
    val long = "top/" + "d".repeat(120) + "/file.txt"
    val tar = TestTar()
    tar.pax("path" to long)
    tar.entry("truncated", '0', "pax".toByteArray())
    val gnu = "top/" + "g".repeat(150)
    tar.entry("././@LongLink", 'L', (gnu + "\u0000").toByteArray())
    tar.entry("short", '0', "gnu".toByteArray())
    tar.entry("next", '0', "plain".toByteArray(), prefix = "top/sub") // ustar prefix
    val (dir, n) = extract(tar, 1)
    assertEquals(3, n)
    assertEquals("pax", File(dir, "d".repeat(120) + "/file.txt").readText())
    assertEquals("gnu", File(dir, "g".repeat(150)).readText())
    assertEquals("plain", File(dir, "sub/next").readText())
    assertFalse(File(dir, "truncated").exists())
  }

  @Test fun paxSizeOverridesHeaderSize() {
    val tar = TestTar()
    tar.pax("size" to "5")
    // header size 5 too (writer uses body length); verify stream stays aligned for the next entry
    tar.entry("a/one", '0', "12345".toByteArray())
    tar.entry("a/two", '0', "x".toByteArray())
    val (dir, _) = extract(tar, 1)
    assertEquals("12345", File(dir, "one").readText())
    assertEquals("x", File(dir, "two").readText())
  }

  @Test fun hardLinkCopiesStrippedTarget() {
    val tar = TestTar()
    tar.entry("top/usr/bin/perl", '0', "perl".toByteArray())
    tar.entry("top/usr/bin/perl5", '1', link = "top/usr/bin/perl")
    val (dir, n) = extract(tar, 1)
    assertEquals(2, n)
    assertEquals("perl", File(dir, "usr/bin/perl5").readText())
  }

  @Test fun rejectsTraversalAndKeepsStreamAligned() {
    val tar = TestTar()
    tar.entry("top/../../evil", '0', "bad".toByteArray())
    tar.entry("top/ok", '0', "ok".toByteArray())
    val (dir, n) = extract(tar, 1)
    assertEquals(1, n)
    assertEquals("ok", File(dir, "ok").readText())
    assertFalse(File(dir.parentFile, "evil").exists())
  }

  @Test fun relativeEntryPath() {
    assertEquals("etc/passwd", TarExtractor.relativeEntryPath("./top/etc/passwd", 1))
    assertEquals("etc/passwd", TarExtractor.relativeEntryPath("/top//etc/./passwd", 1))
    assertNull(TarExtractor.relativeEntryPath("top/", 1))
    assertNull(TarExtractor.relativeEntryPath("top/a/../../b", 1))
    assertEquals("top/x", TarExtractor.relativeEntryPath("top/x", 0))
  }

  @Test fun parsesBase256Size() {
    val b = ByteArray(12)
    b[0] = 0x80.toByte()
    b[10] = 0x01
    b[11] = 0x02
    assertEquals(0x0102L, TarExtractor.parseNumber(b, 0, 12))
  }
}

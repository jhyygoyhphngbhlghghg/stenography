package team.mikcoders.neurosteno.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class AssetModelUnpackTest {
    @Test
    fun unpackCopiesRequiredTreeThenIsIdempotent() {
        val dest = File.createTempFile("models", "dir").apply {
            delete()
            mkdirs()
        }
        dest.deleteOnExit()
        val blobs = AssetModelUnpack.REQUIRED.associateWith { rel ->
            "weight:$rel".toByteArray()
        }
        val first = AssetModelUnpack.unpack(dest) { path ->
            val rel = path.removePrefix("${AssetModelUnpack.ASSET_ROOT}/")
            blobs[rel]?.let { ByteArrayInputStream(it) }
        }
        assertTrue(first.ready)
        assertEquals(AssetModelUnpack.REQUIRED.size, first.copied)
        assertTrue(first.missing.isEmpty())
        for (rel in AssetModelUnpack.REQUIRED) {
            val f = File(dest, rel)
            assertTrue(rel, f.isFile)
            assertEquals("weight:$rel", f.readText())
        }
        assertTrue(File(dest, AssetModelUnpack.MARKER).isFile)
        assertFalse(AssetModelUnpack.needed(dest))

        // Second pass must not re-read the required weights. Optional assets
        // (the GGUF, only packed with -PembedLlm) may be probed; here the
        // opener answers null for them, so nothing is copied again.
        val probed = ArrayList<String>()
        val second = AssetModelUnpack.unpack(dest) { path ->
            val rel = path.removePrefix("${AssetModelUnpack.ASSET_ROOT}/")
            probed += rel
            if (rel in AssetModelUnpack.REQUIRED) error("required asset re-opened: $rel") else null
        }
        assertTrue(second.ready)
        assertEquals(0, second.copied)
        assertEquals(0, second.optionalCopied)
        assertEquals(AssetModelUnpack.REQUIRED.size, second.skippedExisting)
        assertTrue(AssetModelUnpack.OPTIONAL.all { it in probed })
    }

    @Test
    fun missingAssetReportsNotReady() {
        val dest = File.createTempFile("models-miss", "dir").apply {
            delete()
            mkdirs()
        }
        dest.deleteOnExit()
        val report = AssetModelUnpack.unpack(dest) { null }
        assertFalse(report.ready)
        assertEquals(AssetModelUnpack.REQUIRED, report.missing)
        assertTrue(AssetModelUnpack.needed(dest))
    }

    @Test
    fun optionalGgufIsCopiedWhenPackedAndNeverReportedMissing() {
        val dest = File.createTempFile("models-llm", "dir").apply {
            delete()
            mkdirs()
        }
        dest.deleteOnExit()
        val gguf = AssetModelUnpack.OPTIONAL.single()
        val blobs = HashMap<String, ByteArray>()
        AssetModelUnpack.REQUIRED.forEach { blobs[it] = "weight:$it".toByteArray() }
        blobs[gguf] = "GGUFv3".toByteArray()

        val report = AssetModelUnpack.unpack(dest) { path ->
            blobs[path.removePrefix("${AssetModelUnpack.ASSET_ROOT}/")]?.let { ByteArrayInputStream(it) }
        }
        assertTrue(report.ready)
        assertTrue(report.missing.isEmpty())
        assertEquals(1, report.optionalCopied)
        assertEquals("GGUFv3", File(dest, gguf).readText())

        // Same tree with an APK that does not ship the GGUF: ready, nothing missing.
        val noLlm = AssetModelUnpack.unpack(dest) { null }
        assertTrue(noLlm.ready)
        assertTrue(noLlm.missing.isEmpty())
        assertEquals(0, noLlm.optionalCopied)
    }

    @Test
    fun neededReRunsWhenALaterBuildStartsShippingTheGguf() {
        val dest = File.createTempFile("models-upgrade", "dir").apply {
            delete()
            mkdirs()
        }
        dest.deleteOnExit()
        AssetModelUnpack.unpack(dest) { path ->
            val rel = path.removePrefix("${AssetModelUnpack.ASSET_ROOT}/")
            if (rel in AssetModelUnpack.REQUIRED) ByteArrayInputStream("x".toByteArray()) else null
        }
        val onlyRequired = { _: String -> false }
        assertFalse(AssetModelUnpack.needed(dest, onlyRequired))

        val assetsWithLlm = { path: String -> path.endsWith(".gguf") }
        assertTrue(AssetModelUnpack.needed(dest, assetsWithLlm))

        val gguf = AssetModelUnpack.OPTIONAL.single()
        AssetModelUnpack.unpack(dest) { path ->
            if (path.endsWith(gguf)) ByteArrayInputStream("GGUFv3".toByteArray()) else null
        }
        assertFalse(AssetModelUnpack.needed(dest, assetsWithLlm))
    }
}

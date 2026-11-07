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

        val second = AssetModelUnpack.unpack(dest) { error("should not open") }
        assertTrue(second.ready)
        assertEquals(0, second.copied)
        assertEquals(AssetModelUnpack.REQUIRED.size, second.skippedExisting)
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
}

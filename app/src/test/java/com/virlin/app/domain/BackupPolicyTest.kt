package com.virlin.app.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class BackupPolicyTest {
    private fun tags(resource: Int): List<Pair<String, Map<String, String>>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val out = mutableListOf<Pair<String, Map<String, String>>>()
        context.resources.getXml(resource).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) out += xml.name to
                    (0 until xml.attributeCount).associate { xml.getAttributeName(it) to xml.getAttributeValue(it) }
                xml.next()
            }
        }
        return out
    }
    @Test @Config(sdk = [26]) fun legacyDevices_doNotBackUpSensitiveDataWithoutEncryption() {
        val rules = tags(R.xml.backup_rules)
        assertFalse(rules.any { it.first == "include" })
        assertTrue(rules.filter { it.first == "exclude" }.map { it.second["domain"] }.containsAll(listOf("database", "file", "sharedpref")))
    }
    @Test @Config(sdk = [28]) fun encryptedBackup_isRequiredOnAndroid9() {
        val includes = tags(R.xml.backup_rules).filter { it.first == "include" }
        assertEquals(3, includes.size)
        assertTrue(includes.all { it.second["requireFlags"] == "clientSideEncryption" })
    }
    @Test @Config(sdk = [31]) fun cloudEncryptionAndDeviceTransfer_areExplicit() {
        val rules = tags(R.xml.data_extraction_rules)
        assertEquals("true", rules.single { it.first == "cloud-backup" }.second["disableIfNoEncryptionCapabilities"])
        assertTrue(rules.any { it.first == "device-transfer" })
        assertEquals(6, rules.count { it.first == "include" })
    }
}

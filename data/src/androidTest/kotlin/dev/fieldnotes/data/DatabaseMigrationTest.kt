package dev.fieldnotes.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), FieldNotesDatabase::class.java,
    )

    @Test fun versionOneUpgradePreservesPendingOfflineEditsAndAddsIndex() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO projects(id,name) VALUES('field','Field observations')")
            execSQL("""INSERT INTO notes(id,projectId,title,body,updatedAt,localRevision,serverVersion,syncState,deleted)
                VALUES('n1','field','Unsynced oak','Must survive',100,1,0,'PENDING',0)""")
            execSQL("""INSERT INTO outbox(sequence,operationId,noteId,localRevision,baseVersion,projectId,title,body,updatedAt,deleted)
                VALUES(1,'op1','n1',1,0,'field','Unsynced oak','Must survive',100,0)""")
            close()
        }
        helper.runMigrationsAndValidate("migration-test", 2, true, FieldNotesDatabase.MIGRATION_1_2).apply {
            query("SELECT title,syncState FROM notes WHERE id='n1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Unsynced oak", it.getString(0))
                assertEquals("PENDING", it.getString(1))
            }
            query("SELECT operationId FROM outbox").use {
                assertTrue(it.moveToFirst())
                assertEquals("op1", it.getString(0))
            }
            query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_notes_updatedAt'").use {
                assertTrue(it.moveToFirst())
            }
            close()
        }
    }

    @Test fun versionTwoUpgradePreservesUnacknowledgedReceiptsAndAddsFairSyncState() {
        helper.createDatabase("migration-v3-test", 2).apply {
            execSQL("INSERT INTO projects(id,name) VALUES('field','Field observations')")
            execSQL("""INSERT INTO notes(id,projectId,title,body,updatedAt,localRevision,serverVersion,syncState,deleted)
                VALUES('n1','field','Pending replay','Must survive',100,1,0,'PENDING',0)""")
            execSQL("""INSERT INTO outbox(sequence,operationId,noteId,localRevision,baseVersion,projectId,title,body,updatedAt,deleted)
                VALUES(9,'pending-op','n1',1,0,'field','Pending replay','Must survive',100,0)""")
            execSQL("INSERT INTO demo_operations(operationId,requestJson,responseJson) VALUES('pending-op','legacy-request','canonical-response')")
            execSQL("INSERT INTO demo_operations(operationId,requestJson,responseJson) VALUES('acknowledged-op','old-request','old-response')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v3-test", 3, true, FieldNotesDatabase.MIGRATION_2_3).apply {
            query("SELECT operationId,requestJson,responseJson FROM demo_operations").use {
                assertEquals(1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals("pending-op", it.getString(0))
                assertEquals("legacy-request", it.getString(1))
                assertEquals("canonical-response", it.getString(2))
            }
            query("SELECT operationId,sequence FROM outbox").use {
                assertTrue(it.moveToFirst())
                assertEquals("pending-op", it.getString(0))
                assertEquals(9L, it.getLong(1))
            }
            query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_notes_syncState'").use {
                assertTrue(it.moveToFirst())
            }
            execSQL("INSERT INTO sync_cursor(id,afterSequence) VALUES(0,9)")
            query("SELECT afterSequence FROM sync_cursor WHERE id=0").use {
                assertTrue(it.moveToFirst())
                assertEquals(9L, it.getLong(0))
            }
            close()
        }
    }
}

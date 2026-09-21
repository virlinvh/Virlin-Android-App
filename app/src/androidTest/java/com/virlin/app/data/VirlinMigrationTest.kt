package com.virlin.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.data.db.CaptureEntity
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant

/**
 * Pass 10 — REAL schema migration v1 → v2 (captures table). A v1 database is created from the
 * exported `schemas/…/1.json`, populated with representative rows through raw SQL (exactly what
 * a Pass 5–9 install holds), migrated with the production [VirlinDatabase.MIGRATION_1_2], and
 * validated against `2.json`. No destructive fallback anywhere.
 */
@RunWith(AndroidJUnit4::class)
class VirlinMigrationTest {

    private val dbName = "virlin-migration-test.db"
    private val t0 = Instant.parse("2026-09-05T09:00:00Z").toEpochMilli()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), VirlinDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    @Test @Throws(IOException::class)
    fun migrate1To2_keepsEveryV1Row_andAddsCaptures() {
        // ---- v1 with data (raw SQL: the v1 code is gone, the file format is not)
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,createdAt,updatedAt,completedAt) VALUES ('p1','Virlin Android App','d','ACTIVE','HIGH',NULL,144000000,$t0,$t0,NULL)")
            execSQL("INSERT INTO workstreams (id,title,projectId,tool,mode,state,priority,pinned,lastHumanAction,waitingFor,nextHumanAction,blockerReason,processingStartedAt,checkAt,snoozedUntil,snoozeReason,currentCycleId,cycleCount,activeTaskId,createdAt,updatedAt,completedAt) " +
                "VALUES ('s4','Agent Development','p1','Antigravity','EXTERNAL','PROCESSING','NORMAL',0,NULL,'Claude','Review diff',NULL,${t0 - 600000},${t0 + 1800000},NULL,NULL,'c1',1,'t_nl',$t0,$t0,NULL)")
            execSQL("INSERT INTO workstreams (id,title,projectId,tool,mode,state,priority,pinned,lastHumanAction,waitingFor,nextHumanAction,blockerReason,processingStartedAt,checkAt,snoozedUntil,snoozeReason,currentCycleId,cycleCount,activeTaskId,createdAt,updatedAt,completedAt) " +
                "VALUES ('s1','Psychology Unit 23',NULL,NULL,'HUMAN','FOCUS','NORMAL',0,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'p_q17',$t0,$t0,NULL)")
            execSQL("INSERT INTO tasks (id,title,description,projectId,workStreamId,parentTaskId,status,sortOrder,estimatedEffort,dueAt,reminderAt,priority,notes,createdAt,updatedAt,completedAt) VALUES ('t_create','Create Mode',NULL,'p1','s4',NULL,'TODO',0,NULL,NULL,NULL,'NORMAL',NULL,$t0,$t0,NULL)")
            execSQL("INSERT INTO tasks (id,title,description,projectId,workStreamId,parentTaskId,status,sortOrder,estimatedEffort,dueAt,reminderAt,priority,notes,createdAt,updatedAt,completedAt) VALUES ('t_nl','Natural Language',NULL,'p1','s4','t_create','IN_PROGRESS',0,2700000,NULL,NULL,'NORMAL',NULL,$t0,$t0,NULL)")
            execSQL("INSERT INTO tasks (id,title,description,projectId,workStreamId,parentTaskId,status,sortOrder,estimatedEffort,dueAt,reminderAt,priority,notes,createdAt,updatedAt,completedAt) VALUES ('p_q17','Question 17',NULL,NULL,'s1',NULL,'IN_PROGRESS',0,NULL,NULL,NULL,'NORMAL',NULL,$t0,$t0,NULL)")
            execSQL("INSERT INTO cycles (id,workStreamId,number,startedAt,handedOffAt,endedAt,seq) VALUES ('c1','s4',1,${t0 - 900000},${t0 - 600000},NULL,1)")
            execSQL("INSERT INTO focus_sessions (id,workStreamId,cycleId,startedAt,endedAt,taskId,seq) VALUES ('fs1','s1',NULL,${t0 - 300000},NULL,'p_q17',1)")
            execSQL("INSERT INTO context_snapshots (id,workStreamId,cycleId,createdAt,reason,lastHumanAction,waitingFor,nextHumanAction,checkAt,contextLabel,note,taskId,seq) VALUES ('cs1','s4','c1',${t0 - 600000},'HANDOFF','Sent prompt','Claude','Review diff',${t0 + 1800000},NULL,NULL,'t_nl',1)")
            execSQL("INSERT INTO events (id,workStreamId,type,at,cycleId,fromState,toState,detail,seq) VALUES ('e1','s4','HANDOFF',${t0 - 600000},'c1','FOCUS','PROCESSING',NULL,1)")
            execSQL("INSERT INTO meta (key,value) VALUES ('demo_seed','v1')")
            close()
        }

        // ---- migrate with the production migration; Room validates the result against 2.json
        helper.runMigrationsAndValidate(dbName, 2, true, VirlinDatabase.MIGRATION_1_2).close()

        // ---- open through the real builder (same migrations as production) and read via DAOs
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, dbName)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals("Virlin Android App", db.projects().byId("p1")!!.title)
                val s4 = db.workStreams().byId("s4")!!
                assertEquals("PROCESSING", s4.state); assertEquals("EXTERNAL", s4.executionPreference); assertEquals("t_nl", s4.activeTaskId); assertEquals(t0 + 1800000, s4.checkAt!!.toEpochMilli())
                assertEquals("FOCUS", db.workStreams().byId("s1")!!.state)
                assertEquals(3, db.tasks().all().size); assertEquals("t_create", db.tasks().byId("t_nl")!!.parentTaskId)
                assertEquals(1, db.cycles().byWorkStream("s4").size)
                assertNotNull(db.focusSessions().open("s1"))
                assertEquals("Review diff", db.snapshots().latest("s4")!!.nextHumanAction)
                assertEquals(1, db.events().byWorkStream("s4").size)
                assertEquals("v1", db.meta().get("demo_seed"))

                // new table works
                assertEquals(0, db.captures().count())
                db.captures().upsert(CaptureEntity("cap1", CaptureType.PROMPT.name, "line 1\nline 2", null, null, null, "s1", null,
                    CaptureStatus.INBOX.name, null, Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t0), null))
                assertEquals("line 1\nline 2", db.captures().byId("cap1")!!.content)
                assertEquals(1, db.captures().byStatus("INBOX").size)
            }
        } finally { db.close(); ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName) }
    }

    @Test fun migrate2To3_preservesModeAsExecutionPreference() {
        helper.createDatabase(dbName, 2).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,createdAt,updatedAt,completedAt) VALUES ('p1','Virlin Android App','d','ACTIVE','HIGH',NULL,144000000,$t0,$t0,NULL)")
            execSQL("INSERT INTO workstreams (id,title,projectId,tool,mode,state,priority,pinned,lastHumanAction,waitingFor,nextHumanAction,blockerReason,processingStartedAt,checkAt,snoozedUntil,snoozeReason,currentCycleId,cycleCount,activeTaskId,createdAt,updatedAt,completedAt) " +
                "VALUES ('s4','Agent Development','p1','Antigravity','EXTERNAL','PROCESSING','NORMAL',0,NULL,'Claude','Review diff',NULL,${t0 - 600000},${t0 + 1800000},NULL,NULL,'c1',1,'t_nl',$t0,$t0,NULL)")
            execSQL("INSERT INTO workstreams (id,title,projectId,tool,mode,state,priority,pinned,lastHumanAction,waitingFor,nextHumanAction,blockerReason,processingStartedAt,checkAt,snoozedUntil,snoozeReason,currentCycleId,cycleCount,activeTaskId,createdAt,updatedAt,completedAt) " +
                "VALUES ('s1','Psychology Unit 23',NULL,NULL,'HUMAN','FOCUS','NORMAL',0,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'p_q17',$t0,$t0,NULL)")
            execSQL("INSERT INTO tasks (id,title,description,projectId,workStreamId,parentTaskId,status,sortOrder,estimatedEffort,dueAt,reminderAt,priority,notes,createdAt,updatedAt,completedAt) VALUES ('t_nl','Natural Language',NULL,'p1','s4','t_create','IN_PROGRESS',0,2700000,NULL,NULL,'NORMAL',NULL,$t0,$t0,NULL)")
            execSQL("INSERT INTO tasks (id,title,description,projectId,workStreamId,parentTaskId,status,sortOrder,estimatedEffort,dueAt,reminderAt,priority,notes,createdAt,updatedAt,completedAt) VALUES ('t_create','Create Mode',NULL,'p1','s4',NULL,'TODO',0,NULL,NULL,NULL,'NORMAL',NULL,$t0,$t0,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(dbName, 3, true, VirlinDatabase.MIGRATION_2_3).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, dbName)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals("HUMAN", db.projects().byId("p1")!!.defaultExecutionMode)
                assertEquals("EXTERNAL", db.workStreams().byId("s4")!!.executionPreference)
                assertEquals("HUMAN", db.workStreams().byId("s1")!!.executionPreference)
                assertEquals("INHERIT", db.tasks().byId("t_nl")!!.executionPreference)
                assertEquals("INHERIT", db.tasks().byId("t_create")!!.executionPreference)
            }
        } finally { db.close(); ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName) }
    }

    @Test fun migrate3To4_addsNoteDocuments_preservesCaptures() {
        helper.createDatabase(dbName, 3).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,defaultExecutionMode,createdAt,updatedAt,completedAt) VALUES ('p1','Virlin Android App','d','ACTIVE','HIGH',NULL,144000000,'HUMAN',$t0,$t0,NULL)")
            execSQL("INSERT INTO captures (id,type,content,title,sourceUrl,projectId,workStreamId,taskId,status,convertedTaskId,createdAt,updatedAt,archivedAt) VALUES ('cap_note','NOTE','hello note','Hi',NULL,NULL,NULL,NULL,'INBOX',NULL,$t0,$t0,NULL)")
            execSQL("INSERT INTO captures (id,type,content,title,sourceUrl,projectId,workStreamId,taskId,status,convertedTaskId,createdAt,updatedAt,archivedAt) VALUES ('cap_prompt','PROMPT','sys prompt',NULL,NULL,NULL,NULL,NULL,'INBOX',NULL,$t0,$t0,NULL)")
            execSQL("INSERT INTO captures (id,type,content,title,sourceUrl,projectId,workStreamId,taskId,status,convertedTaskId,createdAt,updatedAt,archivedAt) VALUES ('cap_link','LINK','','L','https://example.com',NULL,NULL,NULL,'INBOX',NULL,$t0,$t0,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(dbName, 4, true, VirlinDatabase.MIGRATION_3_4).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, dbName)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals(3, db.captures().count())
                assertEquals("NOTE", db.captures().byId("cap_note")!!.type)
                assertEquals("PROMPT", db.captures().byId("cap_prompt")!!.type)
                assertEquals("https://example.com", db.captures().byId("cap_link")!!.sourceUrl)
                assertEquals(0, db.noteDocuments().count())
                db.noteDocuments().upsert(
                    com.virlin.app.data.db.NoteDocumentEntity(
                        "n1", "cap_note", "Hi",
                        """{"v":1,"blocks":[{"id":"b1","type":"TEXT","text":"hello","checked":false,"collapsed":false,"marks":[],"children":[]}]}""",
                        Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t0)
                    )
                )
                assertEquals(1, db.noteDocuments().count())
                assertEquals("cap_note", db.noteDocuments().byCaptureId("cap_note")!!.captureItemId)
            }
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
        }
    }

    @Test fun migrate4To5_addsPromptDocuments_preservesNotes() {
        helper.createDatabase(dbName, 4).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,defaultExecutionMode,createdAt,updatedAt,completedAt) VALUES ('p1','Virlin Android App','d','ACTIVE','HIGH',NULL,144000000,'HUMAN',$t0,$t0,NULL)")
            execSQL("INSERT INTO captures (id,type,content,title,sourceUrl,projectId,workStreamId,taskId,status,convertedTaskId,createdAt,updatedAt,archivedAt) VALUES ('cap_note','NOTE','hello note','Hi',NULL,NULL,NULL,NULL,'INBOX',NULL,$t0,$t0,NULL)")
            execSQL("INSERT INTO captures (id,type,content,title,sourceUrl,projectId,workStreamId,taskId,status,convertedTaskId,createdAt,updatedAt,archivedAt) VALUES ('cap_prompt','PROMPT','sys prompt','P',NULL,NULL,NULL,NULL,'INBOX',NULL,$t0,$t0,NULL)")
            execSQL(
                "INSERT INTO note_documents (id,captureItemId,title,documentJson,createdAt,updatedAt) VALUES (" +
                    "'n1','cap_note','Hi'," +
                    "'{\"v\":1,\"blocks\":[{\"id\":\"b1\",\"type\":\"TEXT\",\"text\":\"hello\",\"checked\":false,\"collapsed\":false,\"marks\":[],\"children\":[]}]}'," +
                    "$t0,$t0)"
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 5, true, VirlinDatabase.MIGRATION_4_5).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, dbName)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals(2, db.captures().count())
                assertEquals(1, db.noteDocuments().count())
                assertEquals(0, db.promptDocuments().count())
                db.promptDocuments().upsert(
                    com.virlin.app.data.db.PromptDocumentEntity(
                        "prm1", "cap_prompt", "P", "desc", "[]",
                        """{"v":1,"blocks":[{"id":"b1","type":"TEXT","text":"sys","checked":false,"collapsed":false,"marks":[],"children":[]}]}""",
                        Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t0)
                    )
                )
                assertEquals(1, db.promptDocuments().count())
                assertEquals("cap_prompt", db.promptDocuments().byCaptureId("cap_prompt")!!.captureItemId)
            }
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
        }
    }

    @Test fun migrate5To6_addsAttachmentDocuments_preservesPrompts() {
        helper.createDatabase(dbName, 5).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,defaultExecutionMode,createdAt,updatedAt,completedAt) VALUES ('p1','Virlin Android App','d','ACTIVE','HIGH',NULL,144000000,'HUMAN',$t0,$t0,NULL)")
            execSQL("INSERT INTO captures (id,type,content,title,sourceUrl,projectId,workStreamId,taskId,status,convertedTaskId,createdAt,updatedAt,archivedAt) VALUES ('cap_prompt','PROMPT','sys','P',NULL,NULL,NULL,NULL,'INBOX',NULL,$t0,$t0,NULL)")
            execSQL(
                "INSERT INTO prompt_documents (id,captureItemId,title,description,tagsJson,documentJson,createdAt,updatedAt) VALUES (" +
                    "'prm1','cap_prompt','P','d','[]'," +
                    "'{\"v\":1,\"blocks\":[{\"id\":\"b1\",\"type\":\"TEXT\",\"text\":\"sys\",\"checked\":false,\"collapsed\":false,\"marks\":[],\"children\":[]}]}'," +
                    "$t0,$t0)"
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 6, true, VirlinDatabase.MIGRATION_5_6).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, dbName)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals(1, db.captures().count())
                assertEquals(1, db.promptDocuments().count())
                assertEquals(0, db.attachmentDocuments().count())
                db.attachmentDocuments().upsert(
                    com.virlin.app.data.db.AttachmentDocumentEntity(
                        "att1", "cap_file", "doc.pdf", "application/pdf", 1200L,
                        "att1/original", "PDF",
                        Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t0)
                    )
                )
                assertEquals(1, db.attachmentDocuments().count())
                assertEquals("cap_file", db.attachmentDocuments().byCaptureId("cap_file")!!.captureItemId)
            }
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
        }
    }

    @Test fun migrate7To8_addsProjectIconPath_preservesProjects() {
        helper.createDatabase(dbName, 7).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,defaultExecutionMode,createdAt,updatedAt,completedAt) VALUES ('p1','Virlin Android App','d','ACTIVE','HIGH',NULL,144000000,'HUMAN',$t0,$t0,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(dbName, 8, true, VirlinDatabase.MIGRATION_7_8).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, dbName)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                val p = db.projects().byId("p1")!!
                assertEquals("Virlin Android App", p.title)
                assertEquals(null, p.iconPath)                                   // existing rows: no custom icon
                db.projects().upsert(p.copy(iconPath = "p1/icon-1.png"))
                assertEquals("p1/icon-1.png", db.projects().byId("p1")!!.iconPath)
            }
        } finally { db.close() }
    }

    @Test fun migrate6To7_addsVoiceDocuments_preservesAttachments() {
        helper.createDatabase(dbName, 6).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,defaultExecutionMode,createdAt,updatedAt,completedAt) VALUES ('p1','Virlin Android App','d','ACTIVE','HIGH',NULL,144000000,'HUMAN',$t0,$t0,NULL)")
            execSQL("INSERT INTO captures (id,type,content,title,sourceUrl,projectId,workStreamId,taskId,status,convertedTaskId,createdAt,updatedAt,archivedAt) VALUES ('cap_file','FILE','doc.pdf','doc.pdf',NULL,NULL,NULL,NULL,'INBOX',NULL,$t0,$t0,NULL)")
            execSQL(
                "INSERT INTO attachment_documents (id,captureItemId,displayName,mimeType,sizeBytes,relativePath,kind,createdAt,updatedAt) VALUES (" +
                    "'att1','cap_file','doc.pdf','application/pdf',1200,'att1/original','PDF',$t0,$t0)"
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 7, true, VirlinDatabase.MIGRATION_6_7).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, dbName)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals(1, db.captures().count())
                assertEquals(1, db.attachmentDocuments().count())
                assertEquals(0, db.voiceDocuments().count())
                db.voiceDocuments().upsert(
                    com.virlin.app.data.db.VoiceDocumentEntity(
                        "vox1", "cap_voice", "Meeting Ideas", "[]",
                        Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t0)
                    )
                )
                assertEquals(1, db.voiceDocuments().count())
                assertEquals("cap_voice", db.voiceDocuments().byCaptureId("cap_voice")!!.captureItemId)
            }
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
        }
    }

    @Test fun freshInstall_isV8_andNoMigrationNeeded() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("virlin-fresh-test.db")
        val db = Room.databaseBuilder(ctx, VirlinDatabase::class.java, "virlin-fresh-test.db").addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals(0, db.captures().count())
                assertEquals(0, db.noteDocuments().count())
                assertEquals(0, db.promptDocuments().count())
                assertEquals(0, db.attachmentDocuments().count())
                assertEquals(0, db.voiceDocuments().count())
            }
            assertEquals(8, db.openHelper.readableDatabase.version)
        } finally { db.close(); ctx.deleteDatabase("virlin-fresh-test.db") }
    }
}

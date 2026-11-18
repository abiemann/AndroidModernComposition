package dev.fieldnotes.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(@PrimaryKey val id: String, val name: String)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"])],
    indices = [Index("projectId"), Index("updatedAt")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val localRevision: Long,
    val serverVersion: Long,
    val syncState: String,
    val deleted: Boolean,
)

/** Immutable edit snapshots survive process death and ambiguous network failures. */
@Entity(tableName = "outbox", indices = [Index(value = ["operationId"], unique = true), Index("noteId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val operationId: String,
    val noteId: String,
    val localRevision: Long,
    val baseVersion: Long,
    val projectId: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val deleted: Boolean,
) {
    fun mutation() = Mutation(operationId, baseVersion, projectId, title, body, updatedAt, deleted)
}

@Entity(tableName = "conflicts")
data class ConflictEntity(@PrimaryKey val noteId: String, val remoteJson: String)

@Entity(tableName = "demo_notes")
data class DemoNoteEntity(@PrimaryKey val id: String, val remoteJson: String)

@Entity(tableName = "demo_operations")
data class DemoOperationEntity(@PrimaryKey val operationId: String, val requestJson: String, val responseJson: String)

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes WHERE deleted = 0 OR syncState != 'SYNCED' ORDER BY updatedAt DESC, id")
    fun observeNotes(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE id = :id") fun observeNote(id: String): Flow<NoteEntity?>
    @Query("SELECT * FROM projects ORDER BY name") fun observeProjects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: String): NoteEntity?
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ProjectEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putNote(note: NoteEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertProjects(projects: List<ProjectEntity>)
    @Query("UPDATE projects SET name = :name WHERE id = :id") suspend fun renameProject(id: String, name: String)
    @Insert suspend fun enqueue(operation: OutboxEntity)
    @Query("""SELECT outbox.* FROM outbox INNER JOIN notes ON notes.id = outbox.noteId
        WHERE notes.syncState = 'PENDING' ORDER BY outbox.sequence LIMIT 1""")
    suspend fun nextOperation(): OutboxEntity?
    @Query("SELECT COUNT(*) FROM outbox WHERE noteId = :id") suspend fun pendingCount(id: String): Int
    @Query("SELECT COUNT(*) FROM notes WHERE syncState = 'CONFLICT'") suspend fun conflictCount(): Int
    @Query("SELECT COUNT(*) FROM notes WHERE syncState = 'REJECTED'") suspend fun rejectedCount(): Int
    @Query("DELETE FROM outbox WHERE operationId = :operationId") suspend fun acknowledge(operationId: String)
    @Query("DELETE FROM outbox WHERE noteId = :id") suspend fun discardPending(id: String)
    @Query("UPDATE outbox SET baseVersion = :version WHERE noteId = :id") suspend fun rebasePending(id: String, version: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConflict(conflict: ConflictEntity)
    @Query("SELECT * FROM conflicts WHERE noteId = :id") suspend fun conflict(id: String): ConflictEntity?
    @Query("DELETE FROM conflicts WHERE noteId = :id") suspend fun removeConflict(id: String)
    @Query("SELECT * FROM demo_notes ORDER BY id") suspend fun demoNotes(): List<DemoNoteEntity>
    @Query("SELECT * FROM demo_notes WHERE id = :id") suspend fun demoNote(id: String): DemoNoteEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDemoNote(note: DemoNoteEntity)
    @Query("SELECT * FROM demo_operations WHERE operationId = :id") suspend fun demoOperation(id: String): DemoOperationEntity?
    @Insert suspend fun putDemoOperation(operation: DemoOperationEntity)
}

@Database(
    entities = [ProjectEntity::class, NoteEntity::class, OutboxEntity::class, ConflictEntity::class,
        DemoNoteEntity::class, DemoOperationEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class FieldNotesDatabase : RoomDatabase() {
    abstract fun notes(): NotesDao

    companion object {
        /** Version 2 adds the ordering index without discarding users' offline edits. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")
            }
        }

        fun create(context: Context, name: String = "field-notes.db"): FieldNotesDatabase =
            Room.databaseBuilder(context, FieldNotesDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

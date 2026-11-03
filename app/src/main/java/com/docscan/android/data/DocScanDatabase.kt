package com.docscan.android.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val position: Int,
    val sourceFile: String,
    val rotation: Int = 0,
    val filter: String = "ORIGINAL",
    val crop: String? = null,
    @ColumnInfo(defaultValue = "70") val filterStrength: Int = 70,
)

data class DocumentSummary(@Embedded val document: DocumentEntity, val pageCount: Int)

@Dao
interface DocumentDao {
    @Query("SELECT d.*, COUNT(p.id) AS pageCount FROM documents d LEFT JOIN pages p ON p.documentId = d.id GROUP BY d.id ORDER BY d.updatedAt DESC")
    fun observeDocuments(): Flow<List<DocumentSummary>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM pages WHERE documentId = :id ORDER BY position, id")
    fun observePages(id: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :id ORDER BY position, id")
    suspend fun pages(id: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun page(id: String): PageEntity?

    @Query("SELECT sourceFile FROM pages")
    suspend fun sourceFiles(): List<String>

    @Insert suspend fun insertDocument(document: DocumentEntity)
    @Insert suspend fun insertPage(page: PageEntity)
    @Update suspend fun updatePage(page: PageEntity)

    @Query("UPDATE documents SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE documents SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}

@Database(entities = [DocumentEntity::class, PageEntity::class], version = 3, exportSchema = true)
abstract class DocScanDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN crop TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN filterStrength INTEGER NOT NULL DEFAULT 70")
            }
        }
        fun open(context: Context, name: String = "docscan.db"): DocScanDatabase =
            Room.databaseBuilder(context, DocScanDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}

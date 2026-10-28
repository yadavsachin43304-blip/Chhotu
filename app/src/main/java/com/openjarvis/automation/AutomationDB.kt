package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

@Dao
interface AutomationEntityDao {
    @Query("SELECT * FROM automations ORDER BY name")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: AutomationEntity)

    @Update
    suspend fun update(automation: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)
}

class AutomationDao(private val d: AutomationEntityDao) {
    suspend fun getAll(): List<AutomationManager.Automation> = d.getAll().map { it.toModel() }
    suspend fun getById(id: String): AutomationManager.Automation? = d.getById(id)?.toModel()
    suspend fun insert(a: AutomationManager.Automation) = d.insert(a.toEntity())
    suspend fun update(a: AutomationManager.Automation) = d.update(a.toEntity())
    suspend fun delete(id: String) = d.delete(id)
}

fun AutomationEntity.toModel(): AutomationManager.Automation {
    val schedule = when (scheduleType) {
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(scheduleDayOfWeek, scheduleHour, scheduleMinute)
        "interval" -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs)
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleIntervalMs)
        else -> AutomationManager.AutomationSchedule.Daily(scheduleHour, scheduleMinute)
    }
    return AutomationManager.Automation(id, name, command, schedule, enabled, lastRun, lastResult, runCount)
}

fun AutomationManager.Automation.toEntity(): AutomationEntity = when (val s = schedule) {
    is AutomationManager.AutomationSchedule.Daily -> AutomationEntity(id, name, command, "daily", s.hour, s.minute, 0, 0, enabled, lastRun, lastResult, runCount)
    is AutomationManager.AutomationSchedule.Weekly -> AutomationEntity(id, name, command, "weekly", s.hour, s.minute, s.dayOfWeek, 0, enabled, lastRun, lastResult, runCount)
    is AutomationManager.AutomationSchedule.Interval -> AutomationEntity(id, name, command, "interval", 0, 0, 0, s.intervalMs, enabled, lastRun, lastResult, runCount)
    is AutomationManager.AutomationSchedule.Once -> AutomationEntity(id, name, command, "once", 0, 0, 0, s.atMs, enabled, lastRun, lastResult, runCount)
}

@Database(entities = [AutomationEntity::class], version = 1)
abstract class AutomationDB : RoomDatabase() {
    abstract fun automationEntityDao(): AutomationEntityDao
    fun automationDao(): AutomationDao = AutomationDao(automationEntityDao())
    
    companion object {
        @Volatile private var INSTANCE: AutomationDB? = null
        
        fun getInstance(context: Context): AutomationDB {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AutomationDB::class.java,
                    "automations.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class AutomationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val id = inputData.getString("automation_id") ?: return Result.failure()
        val command = inputData.getString("automation_command") ?: return Result.failure()
        
        return try {
            val db = AutomationDB.getInstance(applicationContext)
            val dao = db.automationDao()
            
            val automation = dao.getById(id) ?: return Result.failure()
            
            kotlinx.coroutines.delay(2000)
            
            val updated = automation.copy(
                lastRun = System.currentTimeMillis(),
                lastResult = "success",
                runCount = automation.runCount + 1
            )
            dao.update(updated)
            
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
package com.example.chatsystem

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.Flow
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

data class Session(val baseUrl: String, val token: String, val userId: Long, val username: String, val deviceId: String)
data class AuthResp(val token: String, @SerializedName("user_id") val userId: Long, val username: String)
data class UserDto(val id: Long, val username: String, val online: Boolean)
data class MemberDto(val id: Long, val username: String, val online: Boolean)
data class MsgDto(
    val id: Long, @SerializedName("conversation_id") val conversationId: Long,
    @SerializedName("sender_id") val senderId: Long, @SerializedName("sender_name") val senderName: String,
    val text: String, @SerializedName("client_id") val clientId: String,
    @SerializedName("created_at") val createdAt: Long, val status: String?)
data class Conv(
    val id: Long, val kind: String, val name: String, val members: List<MemberDto>,
    @SerializedName("last_message") val lastMessage: MsgDto?)
data class HbCfg(val interval: Int, val timeout: Int)
data class Stats(
    @SerializedName("online_users") val onlineUsers: List<String>,
    @SerializedName("ws_connections") val wsConnections: Int,
    @SerializedName("total_messages") val totalMessages: Int,
    @SerializedName("pending_sync") val pendingSync: Int,
    val redis: Boolean, val postgres: Boolean, val heartbeat: HbCfg)
data class EventDto(val id: Long, val ts: Double, val step: String, val detail: String)

interface Api {
    @POST("api/auth/login/") suspend fun login(@Body b: Map<String, String>): AuthResp
    @POST("api/auth/register/") suspend fun register(@Body b: Map<String, String>): AuthResp
    @GET("api/users/") suspend fun users(): List<UserDto>
    @GET("api/conversations/") suspend fun convs(): List<Conv>
    @POST("api/conversations/") suspend fun createConv(@Body b: Map<String, @JvmSuppressWildcards Any>): Conv
    @GET("api/conversations/{id}/messages/") suspend fun history(@Path("id") id: Long): List<MsgDto>
    @GET("api/sync/") suspend fun sync(@retrofit2.http.Query("after") after: Long): List<MsgDto>
    @PUT("api/presence/config/") suspend fun setHb(@Body b: Map<String, @JvmSuppressWildcards Any>): HbCfg
    @GET("api/monitor/stats/") suspend fun stats(): Stats
    @GET("api/monitor/events/") suspend fun events(@retrofit2.http.Query("since") since: Long = 0): List<EventDto>
}

/** status: sending (chưa lên server) | sent | delivered | recv (tin của người khác). */
@Entity(tableName = "msgs")
data class MsgEntity(
    @PrimaryKey val clientId: String, val serverId: Long?, val convId: Long, val senderId: Long,
    val senderName: String, val text: String, val createdAt: Long, val status: String)

@Dao
interface MsgDao {
    @Query("SELECT * FROM msgs WHERE convId = :c ORDER BY createdAt, serverId") fun observe(c: Long): Flow<List<MsgEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: MsgEntity)
    @Query("SELECT MAX(serverId) FROM msgs") suspend fun maxServerId(): Long?   // last_synced_message_id
    @Query("SELECT * FROM msgs WHERE status = 'sending' ORDER BY createdAt") suspend fun pending(): List<MsgEntity>
    @Query("SELECT * FROM msgs WHERE clientId = :id") suspend fun byClient(id: String): MsgEntity?
    @Query("UPDATE msgs SET serverId = :sid, status = 'sent' WHERE clientId = :cid AND status = 'sending'")
    suspend fun markSent(cid: String, sid: Long)
    @Query("UPDATE msgs SET status = 'delivered' WHERE serverId = :sid AND status != 'recv'")
    suspend fun markDelivered(sid: Long)
    @Query("DELETE FROM msgs") suspend fun clear()
}

@Database(entities = [MsgEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): MsgDao }
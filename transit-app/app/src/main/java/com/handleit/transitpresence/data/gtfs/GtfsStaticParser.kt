package com.handleit.transitpresence.data.gtfs

import androidx.room.*
import com.handleit.transitpresence.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

// ─── Room Entities ────────────────────────────────────────────────────────────

@Entity(tableName = "stops")
data class StopEntity(
    @PrimaryKey val stopId: String,
    val stopName: String,
    val lat: Double,
    val lng: Double,
    val wheelchairBoarding: Int = 0,
) {
    fun toModel() = Stop(stopId, stopName, lat, lng, wheelchairBoarding)
}

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val routeId: String,
    val routeShortName: String,
    val routeLongName: String,
    val routeType: Int,
    val routeColor: String = "",
    val routeTextColor: String = "",
) {
    fun toModel() = Route(routeId, routeShortName, routeLongName, routeType, routeColor, routeTextColor)
}

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val tripId: String,
    val routeId: String,
    val serviceId: String,
    val tripHeadsign: String = "",
    val directionId: Int = 0,
    val shapeId: String = "",
) {
    fun toModel() = Trip(tripId, routeId, serviceId, tripHeadsign, directionId, shapeId)
}

@Entity(tableName = "stop_times", primaryKeys = ["tripId", "stopSequence"])
data class StopTimeEntity(
    val tripId: String,
    val stopId: String,
    val stopSequence: Int,
    val arrivalTime: String,
    val departureTime: String,
) {
    fun toModel() = StopTime(tripId, stopId, stopSequence, arrivalTime, departureTime)
}

@Entity(tableName = "shapes", primaryKeys = ["shapeId", "sequence"])
data class ShapePointEntity(
    val shapeId: String,
    val lat: Double,
    val lng: Double,
    val sequence: Int,
    val distTraveled: Double = 0.0,
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface StopDao {
    @Query("SELECT * FROM stops WHERE stopId = :id")
    suspend fun getById(id: String): StopEntity?

    @Query("""
        SELECT *, (
            6371000 * acos(
                cos(radians(:lat)) * cos(radians(lat)) *
                cos(radians(lng) - radians(:lng)) +
                sin(radians(:lat)) * sin(radians(lat))
            )
        ) AS distance
        FROM stops
        WHERE distance < :radiusMeters
        ORDER BY distance
        LIMIT :limit
    """)
    suspend fun getNearby(lat: Double, lng: Double, radiusMeters: Double, limit: Int = 20): List<StopEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stops: List<StopEntity>)

    @Query("SELECT COUNT(*) FROM stops")
    suspend fun count(): Int
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes WHERE routeId = :id")
    suspend fun getById(id: String): RouteEntity?

    @Query("SELECT * FROM routes ORDER BY routeShortName")
    fun observeAll(): Flow<List<RouteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(routes: List<RouteEntity>)
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE routeId = :routeId")
    suspend fun getByRoute(routeId: String): List<TripEntity>

    @Query("SELECT * FROM trips WHERE tripId = :tripId LIMIT 1")
    suspend fun getById(tripId: String): TripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(trips: List<TripEntity>)
}

@Dao
interface StopTimeDao {
    @Query("SELECT * FROM stop_times WHERE tripId = :tripId ORDER BY stopSequence")
    suspend fun getForTrip(tripId: String): List<StopTimeEntity>

    @Query("""
        SELECT * FROM stop_times
        WHERE stopId = :stopId
        ORDER BY arrivalTime
    """)
    suspend fun getForStop(stopId: String): List<StopTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stopTimes: List<StopTimeEntity>)
}

@Dao
interface ShapeDao {
    @Query("SELECT * FROM shapes WHERE shapeId = :shapeId ORDER BY sequence")
    suspend fun getShape(shapeId: String): List<ShapePointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<ShapePointEntity>)
}

// ─── Room Database ────────────────────────────────────────────────────────────

@Database(
    entities = [
        StopEntity::class,
        RouteEntity::class,
        TripEntity::class,
        StopTimeEntity::class,
        ShapePointEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TransitDatabase : RoomDatabase() {
    abstract fun stopDao(): StopDao
    abstract fun routeDao(): RouteDao
    abstract fun tripDao(): TripDao
    abstract fun stopTimeDao(): StopTimeDao
    abstract fun shapeDao(): ShapeDao
}

// ─── GTFS CSV Parser ──────────────────────────────────────────────────────────

/**
 * Parses a GTFS static ZIP file and populates the Room database.
 * Designed to run once on first launch or when the feed is refreshed.
 */
class GtfsStaticParser @Inject constructor(
    private val db: TransitDatabase,
) {
    suspend fun parseZip(zipStream: InputStream) = withContext(Dispatchers.IO) {
        Timber.i("GTFS: Beginning static feed parse")
        ZipInputStream(zipStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                Timber.d("GTFS: Parsing ${entry.name}")
                val bytes = zip.readBytes()
                when (entry.name) {
                    "stops.txt"      -> parseStops(bytes.inputStream())
                    "routes.txt"     -> parseRoutes(bytes.inputStream())
                    "trips.txt"      -> parseTrips(bytes.inputStream())
                    "stop_times.txt" -> parseStopTimes(bytes.inputStream())
                    "shapes.txt"     -> parseShapes(bytes.inputStream())
                    else -> Timber.v("GTFS: Skipping ${entry.name}")
                }
                entry = zip.nextEntry
            }
        }
        Timber.i("GTFS: Static feed parse complete. Stops=${db.stopDao().count()}")
    }

    private suspend fun parseStops(stream: InputStream) {
        val entities = parseCsv(stream) { row ->
            StopEntity(
                stopId = row["stop_id"] ?: return@parseCsv null,
                stopName = row["stop_name"] ?: "",
                lat = row["stop_lat"]?.toDoubleOrNull() ?: return@parseCsv null,
                lng = row["stop_lon"]?.toDoubleOrNull() ?: return@parseCsv null,
                wheelchairBoarding = row["wheelchair_boarding"]?.toIntOrNull() ?: 0,
            )
        }
        db.stopDao().insertAll(entities)
        Timber.d("GTFS: Inserted ${entities.size} stops")
    }

    private suspend fun parseRoutes(stream: InputStream) {
        val entities = parseCsv(stream) { row ->
            RouteEntity(
                routeId = row["route_id"] ?: return@parseCsv null,
                routeShortName = row["route_short_name"] ?: "",
                routeLongName = row["route_long_name"] ?: "",
                routeType = row["route_type"]?.toIntOrNull() ?: 3,
                routeColor = row["route_color"] ?: "",
                routeTextColor = row["route_text_color"] ?: "",
            )
        }
        db.routeDao().insertAll(entities)
        Timber.d("GTFS: Inserted ${entities.size} routes")
    }

    private suspend fun parseTrips(stream: InputStream) {
        val entities = parseCsv(stream) { row ->
            TripEntity(
                tripId = row["trip_id"] ?: return@parseCsv null,
                routeId = row["route_id"] ?: return@parseCsv null,
                serviceId = row["service_id"] ?: "",
                tripHeadsign = row["trip_headsign"] ?: "",
                directionId = row["direction_id"]?.toIntOrNull() ?: 0,
                shapeId = row["shape_id"] ?: "",
            )
        }
        db.tripDao().insertAll(entities)
        Timber.d("GTFS: Inserted ${entities.size} trips")
    }

    private suspend fun parseStopTimes(stream: InputStream) {
        // Batch insert in chunks to avoid OOM on large feeds
        val batch = mutableListOf<StopTimeEntity>()
        parseCsvStreaming(stream) { row ->
            val entity = StopTimeEntity(
                tripId = row["trip_id"] ?: return@parseCsvStreaming,
                stopId = row["stop_id"] ?: return@parseCsvStreaming,
                stopSequence = row["stop_sequence"]?.toIntOrNull() ?: return@parseCsvStreaming,
                arrivalTime = row["arrival_time"] ?: "",
                departureTime = row["departure_time"] ?: "",
            )
            batch.add(entity)
            if (batch.size >= 5000) {
                db.stopTimeDao().insertAll(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) db.stopTimeDao().insertAll(batch)
        Timber.d("GTFS: Stop times ingested")
    }

    private suspend fun parseShapes(stream: InputStream) {
        val batch = mutableListOf<ShapePointEntity>()
        parseCsvStreaming(stream) { row ->
            val entity = ShapePointEntity(
                shapeId = row["shape_id"] ?: return@parseCsvStreaming,
                lat = row["shape_pt_lat"]?.toDoubleOrNull() ?: return@parseCsvStreaming,
                lng = row["shape_pt_lon"]?.toDoubleOrNull() ?: return@parseCsvStreaming,
                sequence = row["shape_pt_sequence"]?.toIntOrNull() ?: return@parseCsvStreaming,
                distTraveled = row["shape_dist_traveled"]?.toDoubleOrNull() ?: 0.0,
            )
            batch.add(entity)
            if (batch.size >= 5000) {
                db.shapeDao().insertAll(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) db.shapeDao().insertAll(batch)
        Timber.d("GTFS: Shapes ingested")
    }

    // ── CSV utilities ──────────────────────────────────────────────────────

    private fun <T> parseCsv(stream: InputStream, mapper: (Map<String, String>) -> T?): List<T> {
        val results = mutableListOf<T>()
        parseCsvStreaming(stream) { row -> mapper(row)?.let { results.add(it) } }
        return results
    }

    private fun parseCsvStreaming(stream: InputStream, onRow: (Map<String, String>) -> Unit) {
        stream.bufferedReader().useLines { lines ->
            var headers: List<String>? = null
            for (line in lines) {
                if (line.isBlank()) continue
                val values = splitCsvLine(line)
                if (headers == null) {
                    headers = values
                    continue
                }
                val row = headers.zip(values).toMap()
                onRow(row)
            }
        }
    }

    /**
     * Minimal CSV line splitter — handles quoted fields with commas.
     */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        result.add(current.toString().trim())
        return result
    }
}

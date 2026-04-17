package com.handleit.transitpresence.di

import android.content.Context
import androidx.room.Room
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import com.handleit.transitpresence.BuildConfig
import com.handleit.transitpresence.data.gtfs.TransitDatabase
import com.handleit.transitpresence.data.gtfsrt.GtfsRtConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideTransitDatabase(@ApplicationContext context: Context): TransitDatabase =
        Room.databaseBuilder(context, TransitDatabase::class.java, "transit_presence.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideStopDao(db: TransitDatabase) = db.stopDao()
    @Provides fun provideRouteDao(db: TransitDatabase) = db.routeDao()
    @Provides fun provideTripDao(db: TransitDatabase) = db.tripDao()
    @Provides fun provideStopTimeDao(db: TransitDatabase) = db.stopTimeDao()
    @Provides fun provideShapeDao(db: TransitDatabase) = db.shapeDao()

    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()

    @Provides @Singleton
    fun provideGtfsRtConfig(): GtfsRtConfig = GtfsRtConfig(
        vehiclePositionsUrl = BuildConfig.GTFS_RT_VEHICLE_POSITIONS_URL,
        tripUpdatesUrl = BuildConfig.GTFS_RT_TRIP_UPDATES_URL,
        pollIntervalMs = 10_000L,
    )

    @Provides @Singleton
    fun provideFusedLocationClient(@ApplicationContext context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Provides @Singleton
    fun provideGeofencingClient(@ApplicationContext context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context)
}

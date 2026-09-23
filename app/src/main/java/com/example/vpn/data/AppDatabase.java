package com.example.vpn.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ProfileEntity.class}, version = 3, exportSchema = false)   // ⭐ เพิ่มเวอร์ชันเป็น 3 รองรับ V2Ray
public abstract class AppDatabase extends RoomDatabase {

    public abstract ProfileDao profileDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            ctx.getApplicationContext(),
                            AppDatabase.class,
                            "vpn_profiles.db"
                    ).fallbackToDestructiveMigration().build();
                }
            }
        }
        return INSTANCE;
    }
}

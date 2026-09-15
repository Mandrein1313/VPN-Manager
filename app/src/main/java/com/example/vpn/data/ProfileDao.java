package com.example.vpn.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY isFavorite DESC, lastUsedAt DESC, id DESC")
    LiveData<List<ProfileEntity>> observeAll();

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    ProfileEntity getById(long id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(ProfileEntity profile);

    @Delete
    void delete(ProfileEntity profile);

    @Query("UPDATE profiles SET lastUsedAt = :time WHERE id = :id")
    void markUsed(long id, long time);

    @Query("UPDATE profiles SET isFavorite = :fav WHERE id = :id")
    void setFavorite(long id, boolean fav);
}
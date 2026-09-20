package com.example.vpn.data;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.example.vpn.model.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileRepository {

    /**
     * Callback interface สำหรับส่งผลลัพธ์กลับมาบน main thread
     * ใช้โดย ProfileViewModel และ ProfileListActivity
     */
    public interface Callback<T> {
        void onResult(T result);
    }

    private final ProfileDao dao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public ProfileRepository(AppDatabase db) {
        this.dao = db.profileDao();
    }

    /** LiveData ของโปรไฟล์ทั้งหมด (เรียงตาม favorite + lastUsed) */
    public LiveData<List<Profile>> observeAll() {
        return Transformations.map(dao.observeAll(), entities -> {
            List<Profile> out = new ArrayList<>();
            if (entities != null) {
                for (ProfileEntity e : entities) {
                    out.add(e.toDomain());
                }
            }
            return out;
        });
    }

    /** โหลดโปรไฟล์ตาม id (ทำงานบน background thread แล้ว callback บน main) */
    public void getById(long id, Callback<Profile> cb) {
        io.execute(() -> {
            ProfileEntity e = dao.getById(id);
            final Profile p = (e == null) ? null : e.toDomain();
            main.post(() -> cb.onResult(p));
        });
    }

    /**
     * ⭐ หาโปรไฟล์ที่มีชื่อ+host+port เหมือนกัน (ยกเว้นตัวเอง)
     */
    public void findDuplicate(Profile p, Callback<Profile> cb) {
        io.execute(() -> {
            List<ProfileEntity> all = dao.getAllSync();   // ⭐ ใช้ method จาก DAO
            Profile found = null;
            if (all != null) {
                for (ProfileEntity e : all) {
                    if (e.id == p.id) continue;   // ยกเว้นตัวเอง
                    if (e.name != null && e.name.equals(p.name)
                            && e.host != null && e.host.equals(p.host)
                            && e.port == p.port) {
                        found = e.toDomain();
                        break;
                    }
                }
            }
            final Profile result = found;
            main.post(() -> cb.onResult(result));
        });
    }

    /** บันทึกโปรไฟล์ (insert หรือ update) แล้ว callback ด้วย id */
    public void save(Profile p, Callback<Long> cb) {
        io.execute(() -> {
            final long id = dao.upsert(ProfileEntity.fromDomain(p));
            main.post(() -> cb.onResult(id));
        });
    }

    /** ลบโปรไฟล์ */
    public void delete(Profile p) {
        io.execute(() -> dao.delete(ProfileEntity.fromDomain(p)));
    }

    /** อัปเดตเวลาที่ใช้ล่าสุด */
    public void markUsed(long id) {
        io.execute(() -> dao.markUsed(id, System.currentTimeMillis()));
    }

    /** ตั้ง/ยกเลิก favorite */
    public void setFavorite(long id, boolean fav) {
        io.execute(() -> dao.setFavorite(id, fav));
    }
}

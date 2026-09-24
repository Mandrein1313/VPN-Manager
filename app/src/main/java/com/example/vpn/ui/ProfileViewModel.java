package com.example.vpn.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;

import java.util.List;

public class ProfileViewModel extends ViewModel {

    private final ProfileRepository repo;
    private LiveData<List<Profile>> cachedLiveData;   // ⭐ cache

    public ProfileViewModel(ProfileRepository repo) {
        this.repo = repo;
    }

    /** ⭐ คืน LiveData ตัวเดิมทุกครั้ง — ไม่สร้างใหม่ */
    public LiveData<List<Profile>> getProfiles() {
        if (cachedLiveData == null) {
            cachedLiveData = repo.observeAll();
        }
        return cachedLiveData;
    }

    public void save(Profile p, ProfileRepository.Callback<Long> cb) {
        repo.save(p, cb);
    }

    public void delete(Profile p) {
        repo.delete(p);
    }

    public void toggleFavorite(Profile p) {
        repo.setFavorite(p.id, !p.isFavorite);
    }

    public void markUsed(Profile p) {
        repo.markUsed(p.id);
    }

    public ProfileRepository getRepo() { return repo; }
}
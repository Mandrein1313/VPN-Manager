package com.example.vpn.ui;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.vpn.data.ProfileRepository;

public class ProfileViewModelFactory implements ViewModelProvider.Factory {

    private final ProfileRepository repo;

    public ProfileViewModelFactory(ProfileRepository repo) {
        this.repo = repo;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        return (T) new ProfileViewModel(repo);
    }
}

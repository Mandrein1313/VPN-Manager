package com.example.vpn.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class ConnectionPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_MAIN = 0;
    public static final int PAGE_LOG = 1;

    public ConnectionPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == PAGE_LOG) return new LogFragment();
        return new MainFragment();
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
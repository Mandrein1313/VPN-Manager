package com.example.vpn.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class ConnectionPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_MAIN = 0;
    public static final int PAGE_CHART = 1;    // ⭐ ใหม่
    public static final int PAGE_LOG = 2;

    public ConnectionPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case PAGE_CHART:
                return new TrafficChartFragment();
            case PAGE_LOG:
                return new LogFragment();
            case PAGE_MAIN:
            default:
                return new MainFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 3;   // ⭐ เพิ่มเป็น 3 tabs
    }
}

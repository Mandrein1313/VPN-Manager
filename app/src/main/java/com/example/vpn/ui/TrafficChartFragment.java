package com.example.vpn.ui;

import android.graphics.Color;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.vpn.R;
import com.example.vpn.util.TrafficTracker;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TrafficChartFragment extends Fragment {

    private LineChart chart;
    private TextView txtCurrentDown, txtCurrentUp;
    private TextView txtPeakDown, txtPeakUp;
    private TextView txtAvgDown, txtAvgUp;

    private final TrafficTracker tracker = new TrafficTracker();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = this::updateChart;

    private boolean running = false;

    // ⭐ สีเส้นกราฟ
    private static final int COLOR_DOWN = 0xFF00E676;   // เขียว
    private static final int COLOR_UP = 0xFFFFA726;     // ส้ม

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_traffic_chart, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        chart = v.findViewById(R.id.trafficChart);
        txtCurrentDown = v.findViewById(R.id.txtCurrentDown);
        txtCurrentUp = v.findViewById(R.id.txtCurrentUp);
        txtPeakDown = v.findViewById(R.id.txtPeakDown);
        txtPeakUp = v.findViewById(R.id.txtPeakUp);
        txtAvgDown = v.findViewById(R.id.txtAvgDown);
        txtAvgUp = v.findViewById(R.id.txtAvgUp);

        setupChart();
    }

    @Override
    public void onResume() {
        super.onResume();
        startUpdates();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopUpdates();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopUpdates();
    }

    // ============================================================
    // Setup Chart
    // ============================================================
    private void setupChart() {
        // ไม่มี description
        chart.getDescription().setEnabled(false);

        // Touch
        chart.setTouchEnabled(true);
        chart.setDragEnabled(false);
        chart.setScaleEnabled(false);
        chart.setPinchZoom(false);
        chart.setDoubleTapToZoomEnabled(false);

        // Grid
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);

        // X Axis
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(true);
        xAxis.setAxisLineColor(0xFF444444);
        xAxis.setTextColor(0xFF888888);
        xAxis.setTextSize(10f);
        xAxis.setLabelCount(6, false);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                // แสดง -60s ถึง 0s
                int v = (int) value;
                if (v == 0) return "now";
                return v + "s";
            }
        });

        // Y Axis (Left)
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(0x33FFFFFF);
        leftAxis.setAxisLineColor(0xFF444444);
        leftAxis.setTextColor(0xFF888888);
        leftAxis.setTextSize(10f);
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return formatSpeedShort(value);
            }
        });

        // Y Axis (Right) — ซ่อน
        chart.getAxisRight().setEnabled(false);

        // Legend
        Legend legend = chart.getLegend();
        legend.setEnabled(true);
        legend.setTextColor(0xFFDDDDDD);
        legend.setTextSize(11f);
        legend.setForm(Legend.LegendForm.LINE);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.RIGHT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);

        // Animation
        chart.animateX(0);
        chart.setNoDataText("กำลังรอข้อมูล...");
        chart.setNoDataTextColor(0xFF888888);

        // ตั้ง initial empty data
        setData(new ArrayList<>(), new ArrayList<>());
    }

    // ============================================================
    // Start/Stop Update
    // ============================================================
    private void startUpdates() {
        if (running) return;
        running = true;
        handler.removeCallbacks(updateRunnable);
        handler.post(updateRunnable);
    }

    private void stopUpdates() {
        running = false;
        handler.removeCallbacks(updateRunnable);
    }

    // ============================================================
    // Update
    // ============================================================
    private void updateChart() {
        if (!running) return;

        try {
            long rx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
            long tx = TrafficStats.getUidTxBytes(android.os.Process.myUid());
            if (rx < 0) rx = 0;
            if (tx < 0) tx = 0;

            float[] speeds = tracker.update(rx, tx);
            float downKBps = speeds[0];
            float upKBps = speeds[1];

            // อัปเดต Text
            if (txtCurrentDown != null)
                txtCurrentDown.setText(formatSpeed(downKBps));
            if (txtCurrentUp != null)
                txtCurrentUp.setText(formatSpeed(upKBps));

            if (txtPeakDown != null)
                txtPeakDown.setText("Peak " + formatSpeed(tracker.getMaxDownload()));
            if (txtPeakUp != null)
                txtPeakUp.setText("Peak " + formatSpeed(tracker.getMaxUpload()));

            if (txtAvgDown != null)
                txtAvgDown.setText("Avg " + formatSpeed(tracker.getAvgDownload()));
            if (txtAvgUp != null)
                txtAvgUp.setText("Avg " + formatSpeed(tracker.getAvgUpload()));

            // อัปเดต Chart
            setData(tracker.getDownloadHistory(), tracker.getUploadHistory());

        } catch (Exception e) {
            // เพิกเฉย
        }

        // Schedule ครั้งถัดไป
        if (running) {
            handler.postDelayed(updateRunnable, 1000);
        }
    }

    // ============================================================
    // Set Chart Data
    // ============================================================
    private void setData(List<Float> downloadHistory, List<Float> uploadHistory) {
        List<Entry> downEntries = new ArrayList<>();
        List<Entry> upEntries = new ArrayList<>();

        int size = downloadHistory.size();
        int offset = Math.max(0, 60 - size);   // เริ่มจาก -60s

        for (int i = 0; i < size; i++) {
            float x = -(size - 1 - i);   // -N ... 0
            downEntries.add(new Entry(x, downloadHistory.get(i)));
        }

        int sizeUp = uploadHistory.size();
        for (int i = 0; i < sizeUp; i++) {
            float x = -(sizeUp - 1 - i);
            upEntries.add(new Entry(x, uploadHistory.get(i)));
        }

        LineDataSet downSet = createDataSet(downEntries, "Download", COLOR_DOWN);
        LineDataSet upSet = createDataSet(upEntries, "Upload", COLOR_UP);

        LineData data = new LineData(downSet, upSet);

        chart.setData(data);

        // ตั้ง X Axis ให้ fix -60 ถึง 0
        chart.getXAxis().setAxisMinimum(-60f);
        chart.getXAxis().setAxisMaximum(0f);

        // Y Axis — auto หรือ fix
        float maxY = Math.max(tracker.getMaxDownload(), tracker.getMaxUpload());
        if (maxY < 10) maxY = 10;   // ขั้นต่ำ 10 KB/s
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setAxisMaximum(maxY * 1.2f);

        chart.notifyDataSetChanged();
        chart.invalidate();
    }

    private LineDataSet createDataSet(List<Entry> entries, String label, int color) {
        LineDataSet set = new LineDataSet(entries, label);
        set.setColor(color);
        set.setLineWidth(2f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        set.setCubicIntensity(0.2f);
        set.setDrawFilled(true);
        set.setFillColor(color);
        set.setFillAlpha(60);
        set.setHighLightColor(color);
        set.setHighlightEnabled(false);
        return set;
    }

    // ============================================================
    // Format helpers
    // ============================================================
    private String formatSpeed(float kbps) {
        if (kbps < 1) return "0 KB/s";
        if (kbps < 1024) return String.format(Locale.US, "%.1f KB/s", kbps);
        return String.format(Locale.US, "%.2f MB/s", kbps / 1024f);
    }

    private String formatSpeedShort(float kbps) {
        if (kbps < 1) return "0";
        if (kbps < 1024) return String.format(Locale.US, "%.0f", kbps);
        return String.format(Locale.US, "%.1fM", kbps / 1024f);
    }
}
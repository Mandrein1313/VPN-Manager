package com.example.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.vpn.R;
import com.example.vpn.util.StyledToast;
import com.example.vpn.util.CrashHandler;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CrashLogActivity extends AppCompatActivity {

    private ListView listCrashes;
    private ScrollView scrollDetail;
    private TextView txtDetail;
    private TextView txtEmpty;

    private File[] crashFiles;
    private int selectedIndex = -1;

    private final SimpleDateFormat FMT =
            new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_log);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        listCrashes = findViewById(R.id.listCrashes);
        scrollDetail = findViewById(R.id.scrollDetail);
        txtDetail = findViewById(R.id.txtDetail);
        txtEmpty = findViewById(R.id.txtEmpty);

        MaterialButton btnCopy = findViewById(R.id.btnCopy);
        MaterialButton btnClear = findViewById(R.id.btnClear);

        btnCopy.setOnClickListener(v -> copyCurrent());
        btnClear.setOnClickListener(v -> confirmClear());

        listCrashes.setOnItemClickListener((p, v, pos, id) -> {
            selectedIndex = pos;
            showCrash(crashFiles[pos]);
        });

        refreshList();
    }

    private void refreshList() {
        crashFiles = CrashHandler.listCrashes(this);

        if (crashFiles.length == 0) {
            txtEmpty.setVisibility(View.VISIBLE);
            listCrashes.setVisibility(View.GONE);
            scrollDetail.setVisibility(View.GONE);
            return;
        }

        txtEmpty.setVisibility(View.GONE);
        listCrashes.setVisibility(View.VISIBLE);

        List<String> display = new ArrayList<>();
        for (File f : crashFiles) {
            display.add(FMT.format(new Date(f.lastModified()))
                    + "\n" + f.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, display);
        listCrashes.setAdapter(adapter);

        // แสดงตัวแรกอัตโนมัติ
        selectedIndex = 0;
        showCrash(crashFiles[0]);
    }

    private void showCrash(File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            txtDetail.setText(sb.toString());
            scrollDetail.setVisibility(View.VISIBLE);
            scrollDetail.post(() -> scrollDetail.scrollTo(0, 0));
        } catch (Exception e) {
            txtDetail.setText("อ่านไฟล์ไม่ได้: " + e.getMessage());
            scrollDetail.setVisibility(View.VISIBLE);
        }
    }

    private void copyCurrent() {
        if (selectedIndex < 0 || selectedIndex >= crashFiles.length) {
            StyledToast.error(this, "ไม่มี crash log");
            return;
        }
        String text = txtDetail.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Crash Log", text));
            StyledToast.success(this, "คัดลอกแล้ว");
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("ล้าง Crash Log ทั้งหมด?")
                .setMessage("ลบไฟล์ crash ทั้งหมด " + crashFiles.length + " ไฟล์")
                .setPositiveButton("ลบ", (d, w) -> {
                    CrashHandler.clearAll(this);
                    refreshList();
                    txtDetail.setText("");
                    StyledToast.delete(this, "ลบแล้ว");
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }
}

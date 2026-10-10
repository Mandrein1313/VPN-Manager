package com.example.vpn.util;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Singleton bus สำหรับ broadcast สถานะ VPN ระหว่าง Service กับ Activity
 */
public class StatusBus {

    public enum State {
        IDLE,
        CONNECTING_SSH,
        SSH_CONNECTED,
        SOCKS_READY,
        TUN2SOCKS_READY,
        CONNECTED,
        ERROR,
        STOPPED
    }

    public static class Status {
        public final State state;
        public final String message;
        public final long timestamp;

        public Status(State state, String message) {
            this.state = state;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final MutableLiveData<Status> liveStatus =
            new MutableLiveData<>(new Status(State.IDLE, "พร้อมเชื่อมต่อ"));

    public static LiveData<Status> get() {
        return liveStatus;
    }

    public static void post(State state, String message) {
        liveStatus.postValue(new Status(state, message));
    }

    public static Status current() {
        return liveStatus.getValue();
    }
}
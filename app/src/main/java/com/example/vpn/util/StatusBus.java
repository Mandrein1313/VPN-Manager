package com.example.vpn.util;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * ส่งสถานะ VPN จาก Service → UI
 */
public final class StatusBus {

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

    public static final class Status {
        public final State state;
        public final String message;

        public Status(State state, String message) {
            this.state = state;
            this.message = message != null ? message : "";
        }
    }

    private static final MutableLiveData<Status> LIVE = new MutableLiveData<>(
            new Status(State.IDLE, ""));

    private StatusBus() {}

    public static LiveData<Status> get() {
        return LIVE;
    }

    public static Status current() {
        Status s = LIVE.getValue();
        return s != null ? s : new Status(State.IDLE, "");
    }

    public static void post(State state, String message) {
        LIVE.postValue(new Status(state, message));
    }
}

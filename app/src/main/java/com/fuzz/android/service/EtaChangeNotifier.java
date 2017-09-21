package com.fuzz.android.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;

import com.fuzz.android.R;
import com.fuzz.android.activity.PostOrderActivity;
import com.fuzz.android.backend.BackendCom;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Notifies the user when an order ETA has changed.
 */
public class EtaChangeNotifier extends Service {
    public static int NOTIFICATION_REQUEST_CODE = 1132;

    /**
     * Query interval in millis.
     */
    public static int QUERY_INTERVAL = 4000;

    private int orderId;
    private boolean canQuery = true;
    private EtaChangeListener listener;

    public EtaChangeListener getListener() {
        return listener;
    }

    public void setListener(EtaChangeListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        orderId = intent.getIntExtra("order_id", -1);
        return new Binder(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loopQuery();
    }

    private void loopQuery() {
        final android.os.Handler handler = new Handler();

        //  TODO: Increase initial query interval? Consider time it takes for deliverer to take action
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (canQuery) {
                    queryEta();
                    handler.postDelayed(this, QUERY_INTERVAL);
                }
            }
        }, QUERY_INTERVAL);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        canQuery = false;
    }

    private void queryEta() {
        StringBuilder queryString = new StringBuilder("out=delivery_eta");
        queryString.append("&order_id=").append(orderId);
        BackendCom.request(queryString.toString(), new byte[0], new BackendCom.RequestCallback() {
            @Override
            public void onResponse(String response) {
                parseQueryResponse(response);
            }

            @Override
            public void onFailed() {
                //  TODO: Handle failure
            }
        });

        //  TODO: Sticky?
    }

    private void parseQueryResponse(String response) {
        if (response.length() > 0) {
            try {

                JSONObject obj = new JSONObject(response);
                int etaMins = obj.getInt("minutes");
                String deliverer = obj.getString("deliverer");

                notifyUser(etaMins, deliverer);
                if (listener != null) {
                    listener.onEtaChange(orderId, etaMins * 60, 0, deliverer);
                }

            } catch (JSONException ex) {
                // TODO: Handle this?
            }
        }
    }

    private void notifyUser(int etaMins, String deliverer) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        Intent intent = new Intent(this, PostOrderActivity.class);
        intent.putExtra("secs_at_notif", System.currentTimeMillis() / 1000L);
        intent.putExtra("eta_minutes", etaMins);
        intent.putExtra("deliverer", deliverer);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        //  FLAG_UPDATE_CURRENT prevents two different extra values!!
        PendingIntent notificationIntent = PendingIntent.getActivity(this, NOTIFICATION_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this)
                .setContentText(getString(R.string.eta_notification_content, etaMins))
                .setContentTitle(getString(R.string.eta_notification_title))
                .setContentIntent(notificationIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();


        notification.defaults |= Notification.DEFAULT_VIBRATE | Notification.DEFAULT_SOUND;
        notificationManager.notify(orderId, notification);

        stopSelf();
        canQuery = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public interface EtaChangeListener {
        public void onEtaChange(int orderId, int etaSeconds, int secondsPassed, String delivererName);
    }

    public static class Binder extends android.os.Binder {
        private final EtaChangeNotifier service;

        public Binder(EtaChangeNotifier service) {
            this.service = service;
        }

        public EtaChangeNotifier getService() {
            return service;
        }
    }
}

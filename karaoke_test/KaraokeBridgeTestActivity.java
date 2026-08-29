package com.teslalyrics.karaoketest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.netease.cloudmusic.aidl.MediaSessionCallbackParam;

import java.io.Serializable;

/** Active validation for the patched NetEase 9.4.70 build. */
public class KaraokeBridgeTestActivity extends Activity {
    private static final String NETEASE = "com.netease.cloudmusic";
    private static final String ACTION = "BROADCAST_ACTION_INVOKE_MEDIA_SESSION_CALLBACK";
    private static final String CALLBACK = "onCommand";
    private static final String COMMAND = "SepTrack";
    private TextView status;
    private TextView volumeText;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("Tesla Lyrics · 随心唱桥验证");
        title.setTextSize(20f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("仅用于已打补丁的网易云音乐 9.4.70。\n先播放一首支持随心唱的歌曲，再测试开关和人声比例。\n人声比例测试范围 5%–100%，不会发送 0。");
        hint.setTextSize(14f);
        root.addView(hint);

        Button on = new Button(this);
        on.setText("开启随心唱");
        root.addView(on);
        Button off = new Button(this);
        off.setText("关闭随心唱");
        root.addView(off);

        volumeText = new TextView(this);
        volumeText.setText("人声比例：40%");
        volumeText.setTextSize(16f);
        root.addView(volumeText);

        SeekBar bar = new SeekBar(this);
        bar.setMax(95); // UI 0..95 maps to actual 5..100 percent.
        bar.setProgress(35); // 40% initial, no command is sent until user releases.
        root.addView(bar);

        status = new TextView(this);
        status.setText("等待测试");
        status.setTextSize(13f);
        root.addView(status);

        on.setOnClickListener(v -> send(Boolean.TRUE, "开启"));
        off.setOnClickListener(v -> send(Boolean.FALSE, "关闭"));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int pct = 40;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pct = progress + 5;
                volumeText.setText("人声比例：" + pct + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float value = Math.max(0.05f, Math.min(1.0f, pct / 100f));
                send(Float.valueOf(value), "人声=" + pct + "%");
            }
        });
    }

    private void send(Serializable content, String label) {
        try {
            Bundle args = new Bundle();
            args.putSerializable("content", content);
            MediaSessionCallbackParam param = new MediaSessionCallbackParam();
            param.setArg1(COMMAND);
            param.setArg2(0L);
            param.setExtraData(args);

            Intent i = new Intent(ACTION);
            i.setPackage(NETEASE);
            i.putExtra("callbackName", CALLBACK);
            i.putExtra("callbackParam", param);
            sendBroadcast(i);
            status.setText("已发送：" + label + "\ncommand=" + COMMAND + " / payload=" + content.getClass().getSimpleName());
            Toast.makeText(this, "已发送：" + label, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            status.setText("发送失败：" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }
}

/*****************************************************************************
 * VideoPlayerActivity.java
 *****************************************************************************
 * Copyright © 2011-2012 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.gui.video;

import java.io.File;
import java.lang.reflect.Method;

import org.videolan.vlc.EventManager;
import org.videolan.vlc.LibVLC;
import org.videolan.vlc.LibVlcException;
import org.videolan.vlc.R;
import org.videolan.vlc.Util;
import org.videolan.vlc.gui.PreferencesActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

public class VideoPlayerActivity extends Activity {

    public final static String TAG = "VLC/VideoPlayerActivity";

    private SurfaceView mSurface;
    private SurfaceHolder mSurfaceHolder;
    private LibVLC mLibVLC;

    private static final int SURFACE_BEST_FIT = 0;
    private static final int SURFACE_FIT_HORIZONTAL = 1;
    private static final int SURFACE_FIT_VERTICAL = 2;
    private static final int SURFACE_FILL = 3;
    private static final int SURFACE_16_9 = 4;
    private static final int SURFACE_4_3 = 5;
    private static final int SURFACE_ORIGINAL = 6;
    private int mCurrentSize = SURFACE_BEST_FIT;

    /** Overlay */
    private View mOverlayHeader;
    private View mOverlay;
    private static final int OVERLAY_TIMEOUT = 4000;
    private static final int FADE_OUT = 1;
    private static final int SHOW_PROGRESS = 2;
    private static final int SURFACE_SIZE = 3;
    private static final int FADE_OUT_INFO = 4;
    private boolean mDragging;
    private boolean mShowing;
    private SeekBar mSeekbar;
    private TextView mTitle;
    private TextView mSysTime;
    private TextView mBattery;
    private TextView mTime;
    private TextView mLength;
    private TextView mInfo;
    private SeekBar mWheel;
    private ImageButton mAudio;
    private ImageButton mLock;
    private ImageButton mSize;

    // size of the video
    private int mVideoHeight;
    private int mVideoWidth;

    // stop screen from dimming
    private WakeLock mWakeLock;

    //Audio
    private AudioManager mAudioManager;
    private int mAudioMax;
    private int mAudioDisplayRange;
    private float mTouchY, mVol;
    private boolean mIsAudioChanged;
    private String[] mAudioTracks;

    //Wheel
    private static final int WHEEL_DEAD_ZONE = 7;
    private static final int WHEEL_RANGE = 60;
    private int mMiddle = 0;
    private long mPosition = 0;
    private long mSeekTo = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.player);

        // stop screen from dimming
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG);

        /** initialize Views an their Events */
        mOverlayHeader = (View) findViewById(R.id.player_overlay_header);
        mOverlay = (View) findViewById(R.id.player_overlay);

        /* header */
        mTitle = (TextView) findViewById(R.id.player_overlay_title);
        mSysTime = (TextView) findViewById(R.id.player_overlay_systime);
        mBattery = (TextView) findViewById(R.id.player_overlay_battery);

        mTime = (TextView) findViewById(R.id.player_overlay_time);
        mLength = (TextView) findViewById(R.id.player_overlay_length);
        // the info textView is not on the overlay
        mInfo = (TextView) findViewById(R.id.player_overlay_info);

        mWheel = (SeekBar) findViewById(R.id.player_overlay_wheelbar);
        mWheel.setMax((WHEEL_DEAD_ZONE + WHEEL_RANGE) * 2);
        mMiddle = WHEEL_DEAD_ZONE + WHEEL_RANGE;
        mWheel.setProgress(mMiddle);
        mWheel.setOnSeekBarChangeListener(mWheelListener);

        mAudio = (ImageButton) findViewById(R.id.player_overlay_audio);
        mAudio.setOnClickListener(mAudioListener);

        mLock = (ImageButton) findViewById(R.id.player_overlay_lock);
        mLock.setOnClickListener(mLockListener);

        mSize = (ImageButton) findViewById(R.id.player_overlay_size);
        mSize.setOnClickListener(mSizeListener);

        mSurface = (SurfaceView) findViewById(R.id.player_surface);
        mSurfaceHolder = mSurface.getHolder();
        mSurfaceHolder.setFormat(PixelFormat.RGBX_8888);
        mSurfaceHolder.addCallback(mSurfaceCallback);

        mSeekbar = (SeekBar) findViewById(R.id.player_overlay_seekbar);
        mSeekbar.setOnSeekBarChangeListener(mSeekListener);

        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mAudioMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        try {
            LibVLC.useIOMX(this);
            mLibVLC = LibVLC.getInstance();
        } catch (LibVlcException e) {
            e.printStackTrace();
        }

        EventManager em = EventManager.getIntance();
        em.addHandler(eventHandler);

        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        load();
    }

    @Override
    protected void onPause() {
        long time = 0;
        if (mLibVLC.isPlaying()) {
            time = mLibVLC.getTime() - 5000;
            mLibVLC.pause();
        }
        if (mWakeLock.isHeld())
            mWakeLock.release();

        // Save position
        SharedPreferences preferences = getSharedPreferences(PreferencesActivity.NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(PreferencesActivity.LAST_TIME, time);
        editor.commit();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mBatteryReceiver);
        if (mLibVLC != null) {
            mLibVLC.stop();
        }

        EventManager em = EventManager.getIntance();
        em.removeHandler(eventHandler);

        mAudioManager = null;

        super.onDestroy();
    }

    private BroadcastReceiver mBatteryReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            int batteryLevel = intent.getIntExtra("level", 0);
            mBattery.setText(String.format("%d%%", batteryLevel));
        }
    };

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        showOverlay();
        return true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        setSurfaceSize(mVideoWidth, mVideoHeight);
        super.onConfigurationChanged(newConfig);
    }

    public void setSurfaceSize(int width, int height) {
        // store video size
        mVideoHeight = height;
        mVideoWidth = width;
        Message msg = mHandler.obtainMessage(SURFACE_SIZE);
        mHandler.sendMessage(msg);
    }

    /**
     * Lock screen rotation
     */
    private void lockScreen() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        int rotation = display.getOrientation();
        if (Build.VERSION.SDK_INT >= 8 /* Android 2.2 has getRotation */) {
            try {
                Method m = display.getClass().getDeclaredMethod("getRotation");
                rotation = (Integer) m.invoke(display);
            } catch (Exception e) {
                rotation = Surface.ROTATION_0;
            }
        } else {
            rotation = display.getOrientation();
        }
        switch (rotation) {
            case Surface.ROTATION_0:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case Surface.ROTATION_90:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            case Surface.ROTATION_270:
                // FIXME: API Level 9+ (not tested on a device with API Level < 9)
                setRequestedOrientation(8); // SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                break;
        }

        showInfo(R.string.locked, 1000);
        mLock.setBackgroundResource(R.drawable.ic_lock_glow);
    }

    /**
     * Remove screen lock
     */
    private void unlockScreen() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        showInfo(R.string.unlocked, 1000);
        mLock.setBackgroundResource(R.drawable.ic_lock);
    }

    /**
     * Show text in the info view for "duration" milliseconds
     * @param text
     * @param duration
     */
    private void showInfo(String text, int duration) {
        mInfo.setVisibility(View.VISIBLE);
        mInfo.setText(text);
        mHandler.removeMessages(FADE_OUT_INFO);
        mHandler.sendEmptyMessageDelayed(FADE_OUT_INFO, duration);
    }

    private void showInfo(int textid, int duration) {
        mInfo.setVisibility(View.VISIBLE);
        mInfo.setText(textid);
        mHandler.removeMessages(FADE_OUT_INFO);
        mHandler.sendEmptyMessageDelayed(FADE_OUT_INFO, duration);
    }

    /**
     * Show text in the info view
     * @param text
     */
    private void showInfo(String text) {
        mInfo.setVisibility(View.VISIBLE);
        mInfo.setText(text);
        mHandler.removeMessages(FADE_OUT_INFO);
    }

    /**
     * hide the info view with "delay" milliseconds delay
     * @param delay
     */
    private void hideInfo(int delay) {
        mHandler.sendEmptyMessageDelayed(FADE_OUT_INFO, delay);
    }

    /**
     * hide the info view
     */
    private void hideInfo() {
        hideInfo(0);
    }

    /**
     *  Handle libvlc asynchronous events
     */
    private Handler eventHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.getData().getInt("event")) {
                case EventManager.MediaPlayerPlaying:
                    Log.i(TAG, "MediaPlayerPlaying");
                    break;
                case EventManager.MediaPlayerPaused:
                    Log.i(TAG, "MediaPlayerPaused");
                    break;
                case EventManager.MediaPlayerStopped:
                    Log.i(TAG, "MediaPlayerStopped");
                    break;
                case EventManager.MediaPlayerEndReached:
                    Log.i(TAG, "MediaPlayerEndReached");
                    /* Exit player when reach the end */
                    VideoPlayerActivity.this.finish();
                    break;
                default:
                    Log.e(TAG, "Event not handled");
                    break;
            }
            updateOverlayPausePlay();
        }
    };

    /**
     * Handle resize of the surface and the overlay
     */
    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case FADE_OUT:
                    hideOverlay(false);
                    break;
                case SHOW_PROGRESS:
                    int pos = setOverlayProgress();
                    if (!mDragging && mShowing && mLibVLC.isPlaying()) {
                        msg = obtainMessage(SHOW_PROGRESS);
                        sendMessageDelayed(msg, 1000 - (pos % 1000));
                    }
                    break;
                case SURFACE_SIZE:
                    changeSurfaceSize();
                    break;
                case FADE_OUT_INFO:
                    if (mInfo.getVisibility() == View.VISIBLE)
                        mInfo.startAnimation(AnimationUtils.loadAnimation(
                                VideoPlayerActivity.this, android.R.anim.fade_out));
                    mInfo.setVisibility(View.INVISIBLE);
            }
        }
    };

    private void changeSurfaceSize() {
        // get screen size
        int dw = getWindowManager().getDefaultDisplay().getWidth();
        int dh = getWindowManager().getDefaultDisplay().getHeight();

        // calculate aspect ratio
        double ar = (double) mVideoWidth / (double) mVideoHeight;
        // calculate display aspect ratio
        double dar = (double) dw / (double) dh;

        switch (mCurrentSize) {
            case SURFACE_BEST_FIT:
                if (dar < ar)
                    dh = (int) (dw / ar);
                else
                    dw = (int) (dh * ar);
                break;
            case SURFACE_FIT_HORIZONTAL:
                dh = (int) (dw / ar);
                break;
            case SURFACE_FIT_VERTICAL:
                dw = (int) (dh * ar);
                break;
            case SURFACE_FILL:
                break;
            case SURFACE_16_9:
                ar = 16.0 / 9.0;
                if (dar < ar)
                    dh = (int) (dw / ar);
                else
                    dw = (int) (dh * ar);
                break;
            case SURFACE_4_3:
                ar = 4.0 / 3.0;
                if (dar < ar)
                    dh = (int) (dw / ar);
                else
                    dw = (int) (dh * ar);
                break;
            case SURFACE_ORIGINAL:
                dh = mVideoHeight;
                dw = mVideoWidth;
                break;
        }

        mSurfaceHolder.setFixedSize(mVideoWidth, mVideoHeight);
        LayoutParams lp = mSurface.getLayoutParams();
        lp.width = dw;
        lp.height = dh;
        mSurface.setLayoutParams(lp);
        mSurface.invalidate();
    }

    /**
     * show/hide the overlay
     */

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        if (mAudioDisplayRange == 0)
            mAudioDisplayRange = Math.min(
                    getWindowManager().getDefaultDisplay().getWidth(),
                    getWindowManager().getDefaultDisplay().getHeight());

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN:
                mTouchY = event.getRawY();
                mVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                mIsAudioChanged = false;
                break;

            case MotionEvent.ACTION_MOVE:
                float y = event.getRawY();

                int delta = (int) (((mTouchY - y) / mAudioDisplayRange) * mAudioMax);
                int vol = (int) Math.min(Math.max(mVol + delta, 0), mAudioMax);
                if (delta != 0) {
                    mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, AudioManager.FLAG_SHOW_UI);
                    mIsAudioChanged = true;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (!mIsAudioChanged) {
                    if (!mShowing) {
                        showOverlay();
                    } else {
                        hideOverlay(true);
                    }
                }
                break;
        }
        return mIsAudioChanged;
    }

    /**
     * handle changes of the seekbar (slicer)
     */
    private OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {

        public void onStartTrackingTouch(SeekBar seekBar) {
            mDragging = true;
            showOverlay(3600000);
        }

        public void onStopTrackingTouch(SeekBar seekBar) {
            mDragging = false;
            showOverlay();
            hideInfo();
        }

        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
                mLibVLC.setTime(progress);
                setOverlayProgress();
                mTime.setText(Util.millisToString(progress));
                showInfo(Util.millisToString(progress));
            }

        }
    };

    /**
     *
     */
    private OnSeekBarChangeListener mWheelListener = new OnSeekBarChangeListener() {

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mPosition = mLibVLC.getTime();
            mSeekTo = -1;
            showOverlay(3600000);
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // if in dead zone, pause/unpause
            if (mSeekTo < 0)
            {
                doPausePlay();
                showOverlay();
            }
            else
            {
                mLibVLC.setTime(mSeekTo);
                mTime.setText(Util.millisToString(mSeekTo));
            }
            seekBar.setProgress(mMiddle);
            hideInfo();
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser)
                return;
            int delta = progress - mMiddle;
            if (mLibVLC != null && Math.abs(delta) >= WHEEL_DEAD_ZONE) {
                delta -= Math.signum(delta) * WHEEL_DEAD_ZONE;
                mSeekTo = Math.max(0, mPosition + delta * 1000);
            }
            else
                delta = 0;
            if (mSeekTo >= 0)
                showInfo(String.format("%s%ds (%s)", delta >= 0 ? "+" : "", delta, Util.millisToString(mSeekTo)));
        }

    };

    private OnClickListener mAudioListener = new OnClickListener() {
        public void onClick(View v) {
            if (mAudioTracks == null || mAudioTracks.length <= 1)
                return;

            int current = mLibVLC.getAudioTrack() - 1;

            Builder builder = new AlertDialog.Builder(VideoPlayerActivity.this);
            builder.setSingleChoiceItems(mAudioTracks, current, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    mLibVLC.setAudioTrack(which + 1);
                }
            });

            builder.show();
        }
    };

    /**
     *
     */
    private OnClickListener mLockListener = new OnClickListener() {
        boolean isLocked = false;

        public void onClick(View v) {
            if (isLocked) {
                unlockScreen();
                isLocked = false;
            } else {
                lockScreen();
                isLocked = true;
            }
            showOverlay();
        }
    };

    /**
     *
     */
    private OnClickListener mSizeListener = new OnClickListener() {

        @Override
        public void onClick(View v) {
            if (mCurrentSize < SURFACE_ORIGINAL) {
                mCurrentSize++;
            } else {
                mCurrentSize = 0;
            }
            changeSurfaceSize();
            switch (mCurrentSize) {
                case SURFACE_BEST_FIT:
                    showInfo(R.string.surface_best_fit, 1000);
                    break;
                case SURFACE_FIT_HORIZONTAL:
                    showInfo(R.string.surface_fit_horizontal, 1000);
                    break;
                case SURFACE_FIT_VERTICAL:
                    showInfo(R.string.surface_fit_vertical, 1000);
                    break;
                case SURFACE_FILL:
                    showInfo(R.string.surface_fill, 1000);
                    break;
                case SURFACE_16_9:
                    showInfo("16:9", 1000);
                    break;
                case SURFACE_4_3:
                    showInfo("4:3", 1000);
                    break;
                case SURFACE_ORIGINAL:
                    showInfo(R.string.surface_original, 1000);
                    break;
            }
            showOverlay();
        }
    };

    /**
     * attach and disattach surface to the lib
     */
    private SurfaceHolder.Callback mSurfaceCallback = new Callback() {
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            mLibVLC.attachSurface(holder.getSurface(), VideoPlayerActivity.this, width, height);
        }

        public void surfaceCreated(SurfaceHolder holder) {
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            mLibVLC.detachSurface();
        }
    };

    /**
     * show overlay the the default timeout
     */
    private void showOverlay() {
        showOverlay(OVERLAY_TIMEOUT);
    }

    /**
     * show overlay
     */
    private void showOverlay(int timeout) {
        mHandler.sendEmptyMessage(SHOW_PROGRESS);
        if (!mShowing) {
            mShowing = true;
            mOverlayHeader.setVisibility(View.VISIBLE);
            mOverlay.setVisibility(View.VISIBLE);
        }
        Message msg = mHandler.obtainMessage(FADE_OUT);
        if (timeout != 0) {
            mHandler.removeMessages(FADE_OUT);
            mHandler.sendMessageDelayed(msg, timeout);
        }
        if (mAudioTracks == null) {
            mAudioTracks = mLibVLC.getAudioTrackDescription();
            if (mAudioTracks != null && mAudioTracks.length > 1)
                mAudio.setVisibility(View.VISIBLE);
            else
                mAudio.setVisibility(View.GONE);
        }
        updateOverlayPausePlay();
    }

    /**
     * hider overlay
     */
    private void hideOverlay(boolean fromUser) {
        if (mShowing) {
            mHandler.removeMessages(SHOW_PROGRESS);
            Log.i(TAG, "remove View!");
            if (!fromUser) {
                mOverlayHeader.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_out));
                mOverlay.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_out));
            }
            mOverlayHeader.setVisibility(View.INVISIBLE);
            mOverlay.setVisibility(View.INVISIBLE);
            mShowing = false;
        }
    }

    private void updateOverlayPausePlay() {
        if (mLibVLC == null) {
            return;
        }

        if (mLibVLC.isPlaying()) {
            mWheel.setThumb(getResources().getDrawable(R.drawable.wheel_pause));
        } else {
            mWheel.setThumb(getResources().getDrawable(R.drawable.wheel_play));
        }
        mWheel.setThumbOffset(0);

        //force a refresh
        mWheel.layout(mWheel.getLeft() - 1, mWheel.getTop(), mWheel.getRight(), mWheel.getBottom());
        mWheel.layout(mWheel.getLeft() + 1, mWheel.getTop(), mWheel.getRight(), mWheel.getBottom());
    }

    /**
     * play or pause the media
     */
    private void doPausePlay() {
        if (mLibVLC.isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    /**
     * update the overlay
     */
    private int setOverlayProgress() {
        if (mLibVLC == null) {
            return 0;
        }
        int time = (int) mLibVLC.getTime();
        int length = (int) mLibVLC.getLength();
        // Update all view elements

        mSeekbar.setMax(length);
        mSeekbar.setProgress(time);
        mSysTime.setText(DateFormat.format("kk:mm", System.currentTimeMillis()));
        mTime.setText(Util.millisToString(time));
        mLength.setText(Util.millisToString(length));
        return time;
    }

    /**
     *
     */
    private void play() {
        mLibVLC.play();
        if (!mWakeLock.isHeld())
            mWakeLock.acquire();
    }

    /**
     *
     */
    private void pause() {
        mLibVLC.pause();
        if (mWakeLock.isHeld())
            mWakeLock.release();
    }

    /**
     *
     */
    private void load() {
        String location = null;
        String title = null;
        String lastLocation = null;
        long lastTime = 0;
        SharedPreferences preferences = getSharedPreferences(PreferencesActivity.NAME, MODE_PRIVATE);

        if (getIntent().getAction() != null
                && getIntent().getAction().equals(Intent.ACTION_VIEW)) {
            /* Started from external application */
            location = getIntent().getDataString();
        } else {
            /* Started from VideoListActivity */
            location = getIntent().getExtras().getString("itemLocation");
        }

        if (location != null && location.length() > 0) {
            mLibVLC.readMedia(location);
            if (!mWakeLock.isHeld())
                mWakeLock.acquire();

            // Save media for next time, and restore position if it's the same one as before
            lastLocation = preferences.getString(PreferencesActivity.LAST_MEDIA, null);
            lastTime = preferences.getLong(PreferencesActivity.LAST_TIME, 0);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(PreferencesActivity.LAST_MEDIA, location);
            editor.commit();
            if (lastTime > 0 && location.equals(lastLocation))
                mLibVLC.setTime(lastTime);

            title = new File(location).getName();
            int dotIndex = title.lastIndexOf('.');
            if (dotIndex != -1)
                title = title.substring(0, dotIndex);
            mTitle.setText(title);
        }
    }
}

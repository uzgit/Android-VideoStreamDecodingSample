package com.dji.videostreamdecodingsample;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.graphics.drawable.ColorDrawable;
import android.icu.util.Output;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;
import com.dji.videostreamdecodingsample.media.NativeHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import dji.common.airlink.PhysicalSource;
import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.sdk.airlink.OcuSyncLink;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.thirdparty.afinal.core.AsyncTask;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.accessory.speaker.Speaker;

import java.nio.ByteBuffer;

public class MainActivity extends Activity implements DJICodecManager.YuvDataCallback, View.OnClickListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int MSG_WHAT_SHOW_TOAST = 0;
    private static final int MSG_WHAT_UPDATE_TITLE = 1;
    private SurfaceHolder.Callback surfaceCallback;
    private enum DemoType { USE_TEXTURE_VIEW, USE_SURFACE_VIEW, USE_SURFACE_VIEW_DEMO_DECODER}
    private static DemoType demoType = DemoType.USE_TEXTURE_VIEW;
    private VideoFeeder.VideoFeed standardVideoFeeder;

    BaseProduct _product;

    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;
    private TextView titleTv;
    public Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WHAT_SHOW_TOAST:
                    Toast.makeText(getApplicationContext(), (String) msg.obj, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_WHAT_UPDATE_TITLE:
                    if (titleTv != null) {
                        titleTv.setText((String) msg.obj);
                    }
                    break;
                default:
                    break;
            }
        }
    };
    private TextureView videostreamPreviewTtView;
    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;
    private Camera mCamera;
    private DJICodecManager mCodecManager;
    private TextView savePath;
    private TextView frame_info_text;
    private TextView companion_board_status_text;
    private Button screenShot;
    private StringBuilder stringBuilder;
    private int videoViewWidth;
    private int videoViewHeight;
    private int count;

    private ConnectCompanionBoardTask connectCompanionBoardTask;
    private Timer connectCompanionBoardTaskTimer;

    private ActuateTask actuateTask;
    private Timer actuateTaskTimer;

    private NotifierTask notifierTask;
    private Timer notifierTaskTimer;

//    private FlightControllerStateTask flightControllerStateTask;
    private Timer flightControllerStateTaskTimer;

    float command_roll;
    float command_pitch;
    float command_yaw;
    float command_throttle;
    float command_gimbal_tilt;
    boolean command_land = false;
    boolean data_received = false;
    boolean actuate = true;

    SeekBar roll_seekbar;
    SeekBar pitch_seekbar;
    SeekBar yaw_seekbar;
    SeekBar throttle_seekbar;
    SeekBar gimbal_tilt_seekbar;

    TextView roll_text;
    TextView pitch_text;
    TextView yaw_text;
    TextView throttle_text;
    TextView gimbal_tilt_text;

    TextView mode_text;

    EditText cb_address_text;

    Button virtual_sticks_enable_disable_button;
    Button takeoff_button;
    Button land_button;

    private Aircraft aircraft;
    private FlightController flight_controller;
    private FlightControllerState flight_controller_state;

    private String mode = "landed";

    boolean enable_virtual_sticks = false;
    boolean new_gimbal_seekbar_data = false;

    @Override
    protected void onResume() {
        super.onResume();
        initSurfaceOrTextureView();
        notifyStatusChange();
    }

    private void initSurfaceOrTextureView(){
        switch (demoType) {
            case USE_SURFACE_VIEW:
                initPreviewerSurfaceView();
                break;
            case USE_SURFACE_VIEW_DEMO_DECODER:
                /**
                 * we also need init the textureView because the pre-transcoded video steam will display in the textureView
                 */
                initPreviewerTextureView();

                /**
                 * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                 * on surfaceView
                 */
                initPreviewerSurfaceView();
                break;
            case USE_TEXTURE_VIEW:
                initPreviewerTextureView();
                break;
        }
    }

    @Override
    protected void onPause() {
        if (mCamera != null) {
            if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(mReceivedVideoDataListener);
            }
            if (standardVideoFeeder != null) {
                standardVideoFeeder.removeVideoDataListener(mReceivedVideoDataListener);
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager.destroyCodec();
        }
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        initUiMain();
        if (isM300Product()) {
            OcuSyncLink ocuSyncLink = VideoDecodingApplication.getProductInstance().getAirLink().getOcuSyncLink();
            // If your MutltipleLensCamera is set at right or top, you need to change the PhysicalSource to RIGHT_CAM or TOP_CAM.
            ocuSyncLink.assignSourceToPrimaryChannel(PhysicalSource.LEFT_CAM, PhysicalSource.FPV_CAM, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("assignSourceToPrimaryChannel success.");
                    } else {
                        showToast("assignSourceToPrimaryChannel fail, reason: "+ error.getDescription());
                    }
                }
            });
        }
    }

    public static boolean isM300Product() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            return false;
        }
        Model model = DJISDKManager.getInstance().getProduct().getModel();
        return model == Model.MATRICE_300_RTK;
    }

    private void showToast(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        );
    }

    private void updateTitle(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        );
    }

    private void initUiMain() {

        cb_address_text = (EditText) findViewById(R.id.cb_address_text);

        aircraft = (Aircraft) VideoDecodingApplication.getProductInstance();
        flight_controller = aircraft.getFlightController();

        frame_info_text = (TextView) findViewById(R.id.frame_info_textview);
        frame_info_text.setText("Initializing...");

        mode_text = (TextView) findViewById(R.id.mode_textview);
        mode_text.setText("initializing...");

        companion_board_status_text = (TextView) findViewById(R.id.companion_board_status_textview);

        savePath = (TextView) findViewById(R.id.activity_main_save_path);
        screenShot = (Button) findViewById(R.id.activity_main_screen_shot);
        screenShot.setSelected(false);

        titleTv = (TextView) findViewById(R.id.title_tv);
        videostreamPreviewTtView = (TextureView) findViewById(R.id.livestream_preview_ttv);
        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);
        videostreamPreviewSf.setClickable(true);
        videostreamPreviewSf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float rate = VideoFeeder.getInstance().getTranscodingDataRate();
                showToast("current rate:" + rate + "Mbps");
                if (rate < 10) {
                    VideoFeeder.getInstance().setTranscodingDataRate(10.0f);
                    showToast("set rate to 10Mbps");
                } else {
                    VideoFeeder.getInstance().setTranscodingDataRate(3.0f);
                    showToast("set rate to 3Mbps");
                }
            }
        });

        virtual_sticks_enable_disable_button = (Button) findViewById(R.id.virtual_sticks_enable_disable_button);
        takeoff_button = (Button) findViewById(R.id.takeoff_button);
        land_button = (Button) findViewById(R.id.land_button);

//
//        virtual_sticks_enable_disable_button.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//
//                enable_virtual_sticks = ! enable_virtual_sticks;
//
//                if( enable_virtual_sticks )
//                {
//                    flight_controller.setVirtualStickModeEnabled(enable_virtual_sticks, new CommonCallbacks.CompletionCallback() {
//                        @Override
//                        public void onResult(DJIError djiError) {
//                            if( null == djiError )
//                            {
//                                enable_virtual_sticks = false;
//                            }
//                        }
//                    });
//                }
//                else
//                {
//                    flight_controller.setVirtualStickModeEnabled(enable_virtual_sticks, new CommonCallbacks.CompletionCallback() {
//                        @Override
//                        public void onResult(DJIError djiError)
//                        {
//                            if( null == djiError )
//                            {
//                                enable_virtual_sticks = true;
//                            }
//                        }
//                    });
//                }
//            }
//        });

        virtual_sticks_enable_disable_button.setOnClickListener(this);
        takeoff_button.setOnClickListener(this);
        land_button.setOnClickListener(this);

        roll_seekbar = (SeekBar) findViewById(R.id.roll_seekbar);
        pitch_seekbar = (SeekBar) findViewById(R.id.pitch_seekbar);
        yaw_seekbar = (SeekBar) findViewById(R.id.yaw_seekbar);
        throttle_seekbar = (SeekBar) findViewById(R.id.throttle_seekbar);
        gimbal_tilt_seekbar = (SeekBar) findViewById(R.id.gimbal_tilt_seekbar);

        roll_text = (TextView) findViewById(R.id.roll_textview);
        pitch_text = (TextView) findViewById(R.id.pitch_textview);
        yaw_text = (TextView) findViewById(R.id.yaw_textview);
        throttle_text = (TextView) findViewById(R.id.throttle_textview);
        gimbal_tilt_text = (TextView) findViewById(R.id.gimbal_tilt_textview);

        roll_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                float range = seekBar.getMax();
                float idle = range / (float) 2.0;
                float control = 2 * ((float) progress - idle) / range;

                command_roll = control;
                roll_text.setText("Roll: " + String.valueOf(command_roll));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float idle = seekBar.getMax() / (float) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        pitch_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                float range = seekBar.getMax();
                float idle = range / (float) 2.0;
                float control = 2 * ((float) progress - idle) / range;

                command_pitch = control;
                pitch_text.setText("Pitch: " + String.valueOf(command_pitch));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float idle = seekBar.getMax() / (float) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        yaw_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                float range = seekBar.getMax();
                float idle = range / (float) 2.0;
                float control = 2 * ((float) progress - idle) / range;

                command_yaw = control;
                yaw_text.setText("Yaw: " + String.valueOf(command_yaw));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float idle = seekBar.getMax() / (float) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        throttle_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                float range = seekBar.getMax();
                float idle = range / (float) 2.0;
                float control = 2 * ((float) progress - idle) / range;

                command_throttle = control;
                throttle_text.setText("Throttle: " + String.valueOf(command_throttle));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float idle = seekBar.getMax() / (float) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        gimbal_tilt_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                float range = seekBar.getMax();
                float idle = range / (float)2.0;
                float control = 2 * ((float) progress - idle) / range;

                command_gimbal_tilt = control;
                gimbal_tilt_text.setText("Gimbal Tilt: " + String.valueOf(command_gimbal_tilt));

//                new_gimbal_seekbar_data = true;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float idle = seekBar.getMax() / (float) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        if (null == connectCompanionBoardTask) {
            connectCompanionBoardTask = new ConnectCompanionBoardTask();
            connectCompanionBoardTaskTimer = new Timer();
            connectCompanionBoardTaskTimer.schedule(connectCompanionBoardTask, 100, 50);
        }

        if (null == actuateTask) {
            actuateTask = new ActuateTask();
            actuateTaskTimer = new Timer();
            actuateTaskTimer.schedule(actuateTask, 100, 100);
        }

        if( null == notifierTask )
        {
            notifierTask = new NotifierTask();
            notifierTaskTimer = new Timer();
            notifierTaskTimer.schedule(notifierTask, 100, 50);
        }

        if( null == flightControllerStateTaskTimer )
        {
            flightControllerStateTaskTimer = new Timer();
            flightControllerStateTaskTimer.schedule(new TimerTask() {
                @Override
                public void run()
                {
                    flight_controller_state = flight_controller.getState();
                }
            }, 100, 1000);
        }

        set_default_modes();

        updateUIVisibility();
    }

    private void set_default_modes(){
        flight_controller.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        flight_controller.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        flight_controller.setVerticalControlMode(VerticalControlMode.VELOCITY);
        flight_controller.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
    }

    private void updateUIVisibility(){
        switch (demoType) {
            case USE_SURFACE_VIEW:
                videostreamPreviewSf.setVisibility(View.VISIBLE);
                videostreamPreviewTtView.setVisibility(View.GONE);
                break;
            case USE_SURFACE_VIEW_DEMO_DECODER:
                /**
                 * we need display two video stream at the same time, so we need let them to be visible.
                 */
                videostreamPreviewSf.setVisibility(View.VISIBLE);
                videostreamPreviewTtView.setVisibility(View.VISIBLE);
                break;

            case USE_TEXTURE_VIEW:
                videostreamPreviewSf.setVisibility(View.GONE);
                videostreamPreviewTtView.setVisibility(View.VISIBLE);
                break;
        }
    }
    private long lastupdate;
    private void notifyStatusChange() {

        final BaseProduct product = VideoDecodingApplication.getProductInstance();

        Log.d(TAG, "notifyStatusChange: " + (product == null ? "Disconnect" : (product.getModel() == null ? "null model" : product.getModel().name())));
        if (product != null && product.isConnected() && product.getModel() != null) {
            updateTitle(product.getModel().name() + " Connected " + demoType.name());
        } else {
            updateTitle("Disconnected");
        }

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (System.currentTimeMillis() - lastupdate > 1000) {
                    Log.d(TAG, "camera recv video data size: " + size);
                    lastupdate = System.currentTimeMillis();
                }
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.sendDataToDecoder(videoBuffer, size);
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        /**
                         we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                        break;

                    case USE_TEXTURE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.sendDataToDecoder(videoBuffer, size);
                        }
                        break;
                }

            }
        };

        if (null == product || !product.isConnected()) {
            mCamera = null;
            showToast("Disconnected");
        } else {
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                mCamera = product.getCamera();
                mCamera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError != null) {
                            showToast("can't change mode of camera, error:"+djiError.getDescription());
                        }
                    }
                });

                //When calibration is needed or the fetch key frame is required by SDK, should use the provideTranscodedVideoFeed
                //to receive the transcoded video feed from main camera.
                if (demoType == DemoType.USE_SURFACE_VIEW_DEMO_DECODER && isTranscodedVideoFeedNeeded()) {
                    standardVideoFeeder = VideoFeeder.getInstance().provideTranscodedVideoFeed();
                    standardVideoFeeder.addVideoDataListener(mReceivedVideoDataListener);
                    return;
                }
                if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                    VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
                }

            }
        }
    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private void initPreviewerTextureView() {
        videostreamPreviewTtView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable: width " + videoViewWidth + " height " + videoViewHeight);
                if (mCodecManager == null) {
                    mCodecManager = new DJICodecManager(getApplicationContext(), surface, width, height);
                    //For M300RTK, you need to actively request an I frame.
                    mCodecManager.resetKeyFrame();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable2: width " + videoViewWidth + " height " + videoViewHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mCodecManager != null) {
                    mCodecManager.cleanSurface();
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                frame_info_text.setText(String.valueOf(System.currentTimeMillis()));
                connectCompanionBoardTask.set_image_to_send(videostreamPreviewTtView.getBitmap());
            }
        });
    }

    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private void initPreviewerSurfaceView() {
        videostreamPreviewSh = videostreamPreviewSf.getHolder();
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                videoViewWidth = videostreamPreviewSf.getWidth();
                videoViewHeight = videostreamPreviewSf.getHeight();
                Log.d(TAG, "real onSurfaceTextureAvailable3: width " + videoViewWidth + " height " + videoViewHeight);
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager == null) {
                            mCodecManager = new DJICodecManager(getApplicationContext(), holder, videoViewWidth,
                                                                videoViewHeight);
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        // This demo might not work well on P3C and OSMO.
                        NativeHelper.getInstance().init();
                        DJIVideoStreamDecoder.getInstance().init(getApplicationContext(), holder.getSurface());
                        DJIVideoStreamDecoder.getInstance().resume();
                        break;
                }

            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable4: width " + videoViewWidth + " height " + videoViewHeight);
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        //mCodecManager.onSurfaceSizeChanged(videoViewWidth, videoViewHeight, 0);
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        DJIVideoStreamDecoder.getInstance().changeSurface(holder.getSurface());
                        break;
                }

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.cleanSurface();
                            mCodecManager.destroyCodec();
                            mCodecManager = null;
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        DJIVideoStreamDecoder.getInstance().stop();
                        NativeHelper.getInstance().release();
                        break;
                }

            }
        };

        videostreamPreviewSh.addCallback(surfaceCallback);
    }


    @Override
    public void onYuvDataReceived(MediaFormat format, final ByteBuffer yuvFrame, int dataSize, final int width, final int height) {
        //In this demo, we test the YUV data by saving it into JPG files.
        //DJILog.d(TAG, "onYuvDataReceived " + dataSize);
        if (count++ % 30 == 0 && yuvFrame != null) {
            final byte[] bytes = new byte[dataSize];
            yuvFrame.get(bytes);
            //DJILog.d(TAG, "onYuvDataReceived2 " + dataSize);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    // two samples here, it may has other color format.
                    int colorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                    switch (colorFormat) {
                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                            //NV12
                            if (Build.VERSION.SDK_INT <= 23) {
                                oldSaveYuvDataToJPEG(bytes, width, height);
                            } else {
                                newSaveYuvDataToJPEG(bytes, width, height);
                            }
                            break;
                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                            //YUV420P
                            newSaveYuvDataToJPEG420P(bytes, width, height);
                            break;
                        default:
                            break;
                    }

                }
            });
        }
    }

    // For android API <= 23
    private void oldSaveYuvDataToJPEG(byte[] yuvFrame, int width, int height){
        if (yuvFrame.length < width * height) {
            //DJILog.d(TAG, "yuvFrame size is too small " + yuvFrame.length);
            return;
        }

        byte[] y = new byte[width * height];
        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];
        byte[] nu = new byte[width * height / 4]; //
        byte[] nv = new byte[width * height / 4];

        System.arraycopy(yuvFrame, 0, y, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            v[i] = yuvFrame[y.length + 2 * i];
            u[i] = yuvFrame[y.length + 2 * i + 1];
        }
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        for (int j = 0; j < uvWidth / 2; j++) {
            for (int i = 0; i < uvHeight / 2; i++) {
                byte uSample1 = u[i * uvWidth + j];
                byte uSample2 = u[i * uvWidth + j + uvWidth / 2];
                byte vSample1 = v[(i + uvHeight / 2) * uvWidth + j];
                byte vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2];
                nu[2 * (i * uvWidth + j)] = uSample1;
                nu[2 * (i * uvWidth + j) + 1] = uSample1;
                nu[2 * (i * uvWidth + j) + uvWidth] = uSample2;
                nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2;
                nv[2 * (i * uvWidth + j)] = vSample1;
                nv[2 * (i * uvWidth + j) + 1] = vSample1;
                nv[2 * (i * uvWidth + j) + uvWidth] = vSample2;
                nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2;
            }
        }
        //nv21test
        byte[] bytes = new byte[yuvFrame.length];
        System.arraycopy(y, 0, bytes, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            bytes[y.length + (i * 2)] = nv[i];
            bytes[y.length + (i * 2) + 1] = nu[i];
        }
        Log.d(TAG,
              "onYuvDataReceived: frame index: "
                  + DJIVideoStreamDecoder.getInstance().frameIndex
                  + ",array length: "
                  + bytes.length);
        screenShot(bytes, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", width, height);
    }

    private void newSaveYuvDataToJPEG(byte[] yuvFrame, int width, int height){
        if (yuvFrame.length < width * height) {
            //DJILog.d(TAG, "yuvFrame size is too small " + yuvFrame.length);
            return;
        }
        int length = width * height;

        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];
        for (int i = 0; i < u.length; i++) {
            v[i] = yuvFrame[length + 2 * i];
            u[i] = yuvFrame[length + 2 * i + 1];
        }
        for (int i = 0; i < u.length; i++) {
            yuvFrame[length + 2 * i] = u[i];
            yuvFrame[length + 2 * i + 1] = v[i];
        }
        screenShot(yuvFrame,Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", width, height);
    }

    private void newSaveYuvDataToJPEG420P(byte[] yuvFrame, int width, int height) {
        if (yuvFrame.length < width * height) {
            return;
        }
        int length = width * height;

        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];

        for (int i = 0; i < u.length; i ++) {
            u[i] = yuvFrame[length + i];
            v[i] = yuvFrame[length + u.length + i];
        }
        for (int i = 0; i < u.length; i++) {
            yuvFrame[length + 2 * i] = v[i];
            yuvFrame[length + 2 * i + 1] = u[i];
        }
        screenShot(yuvFrame, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", width, height);
    }

    /**
     * Save the buffered data into a JPG image file
     */
    private void screenShot(byte[] buf, String shotDir, int width, int height) {
        File dir = new File(shotDir);
        if (!dir.exists() || !dir.isDirectory()) {
            dir.mkdirs();
        }
        YuvImage yuvImage = new YuvImage(buf,
                ImageFormat.NV21,
                width,
                height,
                null);
        OutputStream outputFile;
        final String path = dir + "/ScreenShot_" + System.currentTimeMillis() + ".jpg";
        try {
            outputFile = new FileOutputStream(new File(path));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "test screenShot: new bitmap output file error: " + e);
            return;
        }
        if (outputFile != null) {
            yuvImage.compressToJpeg(new Rect(0,
                    0,
                    width,
                    height), 100, outputFile);
        }
        try {
            outputFile.close();
        } catch (IOException e) {
            Log.e(TAG, "test screenShot: compress yuv image error: " + e);
            e.printStackTrace();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                displayPath(path);
            }
        });
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {

            case R.id.activity_main_screen_shot:
                handleYUVClick();
                break;

            case R.id.virtual_sticks_enable_disable_button:

                enable_virtual_sticks = ! enable_virtual_sticks;

                virtual_sticks_enable_disable_button.setBackgroundColor(Color.GREEN);
                flight_controller.setVirtualStickModeEnabled(enable_virtual_sticks, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (null == djiError)
                        {
                            virtual_sticks_enable_disable_button.setBackgroundColor(Color.GRAY);
                        }
                        else
                        {
                            virtual_sticks_enable_disable_button.setBackgroundColor(Color.RED);
                        }
                    }
                });
                break;

            case R.id.takeoff_button:

                connectCompanionBoardTask.notify_takeoff();

                takeoff_button.setBackgroundColor(Color.GREEN);
                flight_controller.startTakeoff(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if( null == djiError )
                        {
                            takeoff_button.setBackgroundColor(Color.GRAY);
                        }
                        else
                        {
                            takeoff_button.setBackgroundColor(Color.RED);
                        }
                    }
                });
                break;

            case R.id.land_button:

                connectCompanionBoardTask.notify_landing();

                land_button.setBackgroundColor(Color.GREEN);
                flight_controller.startLanding(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if( null == djiError )
                        {
                            land_button.setBackgroundColor(Color.GRAY);
                        }
                        else
                        {
                            land_button.setBackgroundColor(Color.RED);
                        }
                    }
                });
                break;

            default:
                DemoType newDemoType = null;
                if (v.getId() == R.id.activity_main_screen_texture) {
                    newDemoType = DemoType.USE_TEXTURE_VIEW;
                } else if (v.getId() == R.id.activity_main_screen_surface) {
                    newDemoType = DemoType.USE_SURFACE_VIEW;
                } else if (v.getId() == R.id.activity_main_screen_surface_with_own_decoder) {
                    newDemoType = DemoType.USE_SURFACE_VIEW_DEMO_DECODER;
                }

                if (newDemoType != null && newDemoType != demoType) {
                    // Although finish will trigger onDestroy() is called, but it is not called before OnCreate of new activity.
                    if (mCodecManager != null) {
                        mCodecManager.cleanSurface();
                        mCodecManager.destroyCodec();
                        mCodecManager = null;
                    }
                    demoType = newDemoType;
                    finish();
                    overridePendingTransition(0, 0);
                    startActivity(getIntent());
                    overridePendingTransition(0, 0);
                }
                break;
        }
    }

    private void handleYUVClick() {
        if (screenShot.isSelected()) {
            screenShot.setText("YUV Screen Shot");
            screenShot.setSelected(false);

            switch (demoType) {
                case USE_SURFACE_VIEW:
                case USE_TEXTURE_VIEW:
                    mCodecManager.enabledYuvData(false);
                    mCodecManager.setYuvDataCallback(null);
                    // ToDo:
                    break;
                case USE_SURFACE_VIEW_DEMO_DECODER:
                    DJIVideoStreamDecoder.getInstance().changeSurface(videostreamPreviewSh.getSurface());
                    DJIVideoStreamDecoder.getInstance().setYuvDataListener(null);
                    break;
            }
            savePath.setText("");
            savePath.setVisibility(View.INVISIBLE);
            stringBuilder = null;
        } else {
            screenShot.setText("Live Stream");
            screenShot.setSelected(true);

            switch (demoType) {
                case USE_TEXTURE_VIEW:
                case USE_SURFACE_VIEW:
                    mCodecManager.enabledYuvData(true);
                    mCodecManager.setYuvDataCallback(this);
                    break;
                case USE_SURFACE_VIEW_DEMO_DECODER:
                    DJIVideoStreamDecoder.getInstance().changeSurface(null);
                    DJIVideoStreamDecoder.getInstance().setYuvDataListener(MainActivity.this);
                    break;
            }
            savePath.setText("");
            savePath.setVisibility(View.VISIBLE);
        }
    }

    private void displayPath(String path) {
        if (stringBuilder == null) {
            stringBuilder = new StringBuilder();
        }

        path = path + "\n";
        stringBuilder.append(path);
        savePath.setText(stringBuilder.toString());
    }

    private boolean isTranscodedVideoFeedNeeded() {
        if (VideoFeeder.getInstance() == null) {
            return false;
        }

        return VideoFeeder.getInstance().isFetchKeyFrameNeeded() || VideoFeeder.getInstance()
                                                                               .isLensDistortionCalibrationNeeded();
    }

    private class NotifierTask extends TimerTask {
        public void run()
        {
            if( enable_virtual_sticks )
            {
                virtual_sticks_enable_disable_button.setBackgroundColor(Color.GREEN);
            }
            else
            {
                virtual_sticks_enable_disable_button.setBackgroundColor(Color.GRAY);
            }

            if( connectCompanionBoardTask.is_connected() )
            {
                companion_board_status_text.setText("CB connected!");
            }
            else
            {
                companion_board_status_text.setText("CB disconnected!");
            }

            if( mode.equals("landing") && ! flight_controller_state.areMotorsOn() )
            {
                connectCompanionBoardTask.notify_landed();
            }
            else if( mode.equals("takeoff") && flight_controller_state.isFlying() )
            {
                connectCompanionBoardTask.notify_precision_hover();
            }

            if( ! mode.equals(mode_text.getText())) {
                try {
                    mode_text.setText("Mode: " + String.valueOf(mode));
                } catch (Exception e) {
                    showToast(e.toString());
                    showToast(mode);
                }
            }
        }
    }

    private class ActuateTask extends TimerTask {

        private int control_to_progress(float control, float control_min, float control_max, int progress_min, int progress_max)
        {
            int progress = (int)(progress_min + (control - control_min)*(progress_max - progress_min)/(control_max - control_min));

            return progress;
        }

        private int constrain(int progress, int max)
        {
            int result = progress;
            if( result < 0 )
            {
                result = 0;
            }
            else if( result > max )
            {
                result = max;
            }

            return result;
        }

        private int constrain(int progress, SeekBar seekbar)
        {
            int result = progress;

            if( result < 0 )
            {
                result = 0;
            }
            else if( result > seekbar.getMax() )
            {
                result = seekbar.getMax();
            }

            return result;
        }

        @Override
        public void run()
        {
//            Log.d(TAG, String.valueOf(actuate) + "     " + String.valueOf(data_received));

            if( actuate && data_received )
            {
                try {
                    data_received = false;

                    roll_seekbar.setProgress(constrain(control_to_progress(command_roll, (float) -1.0, (float) 1.0, 0, roll_seekbar.getMax()), roll_seekbar));
                    pitch_seekbar.setProgress(constrain(control_to_progress(command_pitch, (float) -1.0, (float) 1.0, 0, pitch_seekbar.getMax()), pitch_seekbar));
                    yaw_seekbar.setProgress(constrain(control_to_progress(command_yaw, (float) -1.0, (float) 1.0, 0, yaw_seekbar.getMax()), yaw_seekbar));
                    throttle_seekbar.setProgress(constrain(control_to_progress(command_throttle, (float) -1.0, (float) 1.0, 0, throttle_seekbar.getMax()), throttle_seekbar));
//                    gimbal_tilt_seekbar.setProgress( constrain( (int)command_gimbal_tilt*(-1000), 85000));
                    gimbal_tilt_seekbar.setProgress(constrain(control_to_progress(command_gimbal_tilt, (float) -1.0, (float) 1.0, 0, gimbal_tilt_seekbar.getMax()), gimbal_tilt_seekbar));
//                    frame_info_text.setText("Actuating!");

//                    showToast("Actuating...");
//                    Log.d(TAG, String.format("**************************************************************** roll: %.4f, pitch: %.4f, yaw: %.4f, throttle: %.4f, gimbal_tilt:%.4f", command_roll, command_pitch, command_yaw, command_throttle, command_gimbal_tilt));
                }
                catch( Exception e )
                {
                    Log.d(TAG, "actuation error");
                }
            }

            if( command_land && actuate && enable_virtual_sticks )//&& actuate && data_received )// && enable_virtual_sticks )
            {
                command_land = false;

                if( flight_controller_state != null && flight_controller_state.areMotorsOn() ) {
                    flight_controller.startLanding(new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (null == djiError) {
//                            land_button.setBackgroundColor(Color.GRAY);
                            } else {
//                            land_button.setBackgroundColor(Color.RED);
                            }
                        }
                    });
                }
                else
                {
                    connectCompanionBoardTask.notify_landed();
                }
            }

            if( actuate )//&& (data_received || new_gimbal_seekbar_data ) )
            {
                try
                {
                    VideoDecodingApplication.getProductInstance().getGimbal().rotate(new Rotation.Builder().pitch(100 * command_gimbal_tilt)
                            .mode(RotationMode.SPEED)
                            .yaw(Rotation.NO_ROTATION)
                            .roll(Rotation.NO_ROTATION)
                            .time(0)
                            .build(), new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {

                        }
                    });

                    new_gimbal_seekbar_data = false;
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            if( enable_virtual_sticks )
            {
                try
                {
                    FlightControlData flight_control_data = new FlightControlData(command_roll, command_pitch, 50 * command_yaw, command_throttle);
                    flight_controller.sendVirtualStickFlightControlData(flight_control_data, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {

                        }
                    });
                }
                catch( Exception e )
                {
                    e.printStackTrace();
                }
            }
        }
    }

    private class ConnectCompanionBoardTask extends TimerTask {

//        private String host = "192.168.1.115";
//        private String host = "192.168.1.21";
//        private String host = "192.168.50.185";
        private String host;
        private String image_port = "14555";
        private String command_port  = "14556";

        private boolean notified_disconnected = false;
        private boolean notified_connected = false;

        long send_limit = 100;
        long last_send_time;

        Socket image_socket = null;
        Socket command_socket = null;

        OutputStream image_output_stream = null;

        OutputStream command_output_stream = null;
        BufferedReader command_input_stream = null;

        private boolean is_connected = false;

        private Bitmap image_to_send = null;
        private String input_string = null;

        ConnectCompanionBoardTask()
        {
            super();

            last_send_time = System.currentTimeMillis();
        }

        boolean is_connected()
        {
            return is_connected;
        }

        private void open_sockets()
        {
            try{
                image_socket = new Socket(host, Integer.valueOf(image_port));
                command_socket = new Socket(host, Integer.valueOf(command_port));

                image_output_stream = image_socket.getOutputStream();

                command_output_stream = command_socket.getOutputStream();
                command_input_stream = new BufferedReader(new InputStreamReader(command_socket.getInputStream()));

                is_connected = true;

                image_socket.setSoTimeout(1500);
                command_socket.setSoTimeout(1500);

                showToast("Connecting to CB...");
            }
            catch( Exception e )
            {
                close_sockets();
            }
        }

        private void close_sockets()
        {
            try{
                image_socket.close();
            }
            catch( Exception e )
            {
                e.printStackTrace();
            }

            try{
                command_socket.close();
            }
            catch( Exception e )
            {
                e.printStackTrace();
            }

            image_socket = null;
            command_socket = null;

            image_output_stream = null;
            command_input_stream = null;
            command_output_stream = null;

            is_connected = false;
        }

        private void maintain_connection()
        {
            if( ! is_connected ) {
                // try to connect
                try {
                    host = cb_address_text.getText().toString();

                    open_sockets();
                } catch( Exception e ) {
                    e.printStackTrace();
                }
            }
        }

        public void send_bytes(OutputStream output_stream, byte[] byte_array)
        {
            if( null != output_stream ) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int size = byte_array.length;
                            byte[] size_bytes = ByteBuffer.allocate(4).putInt(size).array();

                            output_stream.write(size_bytes);
                            output_stream.write(byte_array);
                        } catch (Exception e) {
                            close_sockets();

                            e.printStackTrace();
                        }
                    }
                });
                thread.start();
            }
        }

        public String get_line()
        {
            String result = null;
            if( null != command_input_stream ) {
                try {
                    result = command_input_stream.readLine();
                } catch (Exception e) {
                    close_sockets();

                    e.printStackTrace();
                }
            }
            return result;
        }

        // this method is used to:
        //  1. initiate a connection if the connection is null
        //  2. send an image if it has been requested
        //  3.read data from the connection if it is connected
        @Override
        public void run()
        {
            maintain_connection();

            send_image();

            read_commands();
        }

        public void send_image()
        {
            if( null != image_to_send ) {
                Bitmap bitmap_image = toGrayScale(image_to_send);
                ByteArrayOutputStream byte_stream = new ByteArrayOutputStream();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap_image.compress(Bitmap.CompressFormat.WEBP_LOSSY, 10, byte_stream);
                }
                byte[] image_byte_array = byte_stream.toByteArray();

                send_bytes(image_output_stream, image_byte_array);

                image_to_send = null;
//                showToast("Sent" + String.valueOf(System.currentTimeMillis()));
            }
        }

        public Bitmap toGrayScale(Bitmap bmpOriginal) {

            int width, height;
            height = bmpOriginal.getHeight();
            width = bmpOriginal.getWidth();

            Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmpGrayscale);
            Paint paint = new Paint();
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
            paint.setColorFilter(f);
            c.drawBitmap(bmpOriginal, 0, 0, paint);
            bmpOriginal.recycle();
            return bmpGrayscale;
        }

        public void set_image_to_send(Bitmap bitmap_image)
        {
            if( null == image_to_send ) {
                this.image_to_send = bitmap_image;
            }
        }

        public void read_commands()
        {
            String[] commands = {"landed", "takeoff", "search", "idle_inflight", "approach", "landing", "+"};

            Thread thread = new Thread(new Runnable() {
                @RequiresApi(api = Build.VERSION_CODES.N)
                @Override
                public void run() {
                    if( null != command_input_stream ) {
                        try {
                            input_string = command_input_stream.readLine();

                            if(null != input_string)
                            {
                                char first_character = input_string.charAt(0);
                                boolean mode_command = Arrays.stream(commands).anyMatch(input_string::equals);

//                                showToast(input_string + " " + String.valueOf(mode_command));
                                if( '+' == first_character )
                                {
                                    String message = input_string.substring(1);
                                    showToast("CB: " + message);
                                }
                                else if(! mode_command )
                                {
                                    try {
                                        String[] commands = input_string.split(",");
                                        command_roll = Float.valueOf(commands[0]);
                                        command_pitch = Float.valueOf(commands[1]);
                                        command_yaw = Float.valueOf(commands[2]);
                                        command_throttle = Float.valueOf(commands[3]);
                                        command_gimbal_tilt = Float.valueOf(commands[4]);

                                        data_received = true;

//                                        Log.d(TAG, String.format("roll: %f ; pitch: %f ; yaw: %f ; throttle: %f ; gimbal_tilt: %f", command_roll, command_pitch, command_yaw, command_throttle, command_gimbal_tilt));
                                    }
                                    catch( Exception e )
                                    {
                                        e.printStackTrace();
                                    }
                                }
                                else if( input_string.equals("landed") )
                                {
                                    notify_landed();
                                }
                                else if( input_string.equals("takeoff") )
                                {
                                    notify_takeoff();
                                }
                                else if( input_string.equals("search") )
                                {
                                    notify_search();
                                }
                                else if( input_string.equals("approach") )
                                {
                                    notify_approach();
                                }
                                else if( input_string.equals("landing"))
                                {
                                    command_land = true;
                                    notify_landing();
                                }
                                else
                                {
                                    showToast("Received invalid command: " + input_string);
                                }
                            }

                        } catch (Exception e) {
                            close_sockets();

                            e.printStackTrace();
                        }
                    }
                }
            });
            thread.start();
        }

        public void notify_landed()
        {
            if( null != image_output_stream )
            {
                String landed_string = "landed";

                mode = landed_string;
                send_bytes(image_output_stream, landed_string.getBytes());
            }
        }

        public void notify_takeoff()
        {
            if( null != image_output_stream )
            {
                String takeoff_string = "takeoff";
                send_bytes(image_output_stream, takeoff_string.getBytes());

                mode = takeoff_string;
            }
        }

        public void notify_search()
        {
            if( null != image_output_stream )
            {
                String search_string = "search";

                mode = search_string;
                send_bytes(image_output_stream, search_string.getBytes());
            }
        }

        public void notify_idle_inflight()
        {
            if( null != image_output_stream )
            {
                String idle_inflight_string = "idle_inflight";

                mode = idle_inflight_string;
                send_bytes(image_output_stream, idle_inflight_string.getBytes());
            }
        }

        public void notify_precision_hover()
        {
            if( null != image_output_stream )
            {
                String precision_hover_string = "precision_hover";

                mode = precision_hover_string;
                send_bytes(image_output_stream, precision_hover_string.getBytes());
            }
        }

        public void notify_approach()
        {
            if( null != image_output_stream )
            {
                String approach_string = "approach";

                mode = approach_string;
                send_bytes(image_output_stream, approach_string.getBytes());
            }
        }

        public void notify_landing()
        {
            if( null != image_output_stream )
            {
                String landing_string = "landing";

                mode = landing_string;
                send_bytes(image_output_stream, landing_string.getBytes());
            }
        }
    }
}

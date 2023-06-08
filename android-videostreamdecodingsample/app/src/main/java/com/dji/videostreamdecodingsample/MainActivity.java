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
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Contacts;
import android.provider.MediaStore;
import android.util.Log;
import android.view.DragEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;
import com.dji.videostreamdecodingsample.media.NativeHelper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
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
import dji.common.gimbal.GimbalState;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.sdk.airlink.OcuSyncLink;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.thirdparty.afinal.core.AsyncTask;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;

public class MainActivity extends Activity implements DJICodecManager.YuvDataCallback, View.OnClickListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int MSG_WHAT_SHOW_TOAST = 0;
    private static final int MSG_WHAT_UPDATE_TITLE = 1;
    private SurfaceHolder.Callback surfaceCallback;

    private enum DemoType {USE_TEXTURE_VIEW, USE_SURFACE_VIEW, USE_SURFACE_VIEW_DEMO_DECODER}

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
    private TextView gimbal_info_text;
    private Button screenShot;
    private StringBuilder stringBuilder;
    private int videoViewWidth;
    private int videoViewHeight;
    private int count;

//    private GimbalInfoTask gimbalInfoTask;
//    private Timer gimbalInfoTaskTimer;

    private CompanionBoardConnector companionBoardConnector;
    private Timer companionBoardConnectorTimer;

    private FCUConnector fcuConnector;
    private Timer fcuConnectorTimer;

    private UIActuator uiActuator;
    private Timer uiActuatorTimer;

    long last_command_received_time = 999;
    double command_roll;
    double command_pitch;
    double command_yaw;
    double command_throttle;
    double command_gimbal_tilt;
    boolean command_land = false;
    boolean data_received = false;

    boolean actuate_gimbal = true;
    boolean actuate = false;
    boolean enable_virtual_sticks = false;

    boolean takeoff = false;
    boolean takeoff_state = false;
    boolean land;

    SeekBar roll_seekbar;
    SeekBar pitch_seekbar;
    SeekBar yaw_seekbar;
    SeekBar throttle_seekbar;
    SeekBar gimbal_tilt_seekbar;
    SeekBar actuate_gimbal_seekbar;
    SeekBar actuate_seekbar;
    SeekBar virtual_stick_enable_seekbar;

    TextView roll_text;
    TextView pitch_text;
    TextView yaw_text;
    TextView throttle_text;
    TextView gimbal_tilt_text;

    private Aircraft aircraft;
    private FlightController flight_controller;

    @Override
    protected void onResume() {
        super.onResume();
        initSurfaceOrTextureView();
        notifyStatusChange();
    }

    private void initSurfaceOrTextureView() {
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

        int i = 0;

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
                        showToast("assignSourceToPrimaryChannel fail, reason: " + error.getDescription());
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

        aircraft = (Aircraft) VideoDecodingApplication.getProductInstance();
        flight_controller = aircraft.getFlightController();

        frame_info_text = (TextView) findViewById(R.id.frame_info_textview);
        frame_info_text.setText("Initializing...");

        gimbal_info_text = (TextView) findViewById(R.id.gimbal_info_textview);

        titleTv = (TextView) findViewById(R.id.title_tv);
        videostreamPreviewTtView = (TextureView) findViewById(R.id.livestream_preview_ttv);
        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);

        roll_seekbar = (SeekBar) findViewById(R.id.roll_seekbar);
        pitch_seekbar = (SeekBar) findViewById(R.id.pitch_seekbar);
        yaw_seekbar = (SeekBar) findViewById(R.id.yaw_seekbar);
        throttle_seekbar = (SeekBar) findViewById(R.id.throttle_seekbar);
        gimbal_tilt_seekbar = (SeekBar) findViewById(R.id.gimbal_tilt_seekbar);
        actuate_gimbal_seekbar = (SeekBar) findViewById(R.id.actuate_gimbal_seekbar);
        actuate_seekbar = (SeekBar) findViewById(R.id.actuate_seekbar);
        virtual_stick_enable_seekbar = (SeekBar) findViewById(R.id.virtual_stick_enable_seekbar);

        roll_text = (TextView) findViewById(R.id.roll_textview);
        pitch_text = (TextView) findViewById(R.id.pitch_textview);
        yaw_text = (TextView) findViewById(R.id.yaw_textview);
        throttle_text = (TextView) findViewById(R.id.throttle_textview);
        gimbal_tilt_text = (TextView) findViewById(R.id.gimbal_tilt_textview);

        roll_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                double range = seekBar.getMax();
                double idle = range / (float) 2.0;
                double control = 2 * ((float) progress - idle) / range;

                command_roll = control;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                double idle = seekBar.getMax() / (double) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        pitch_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                double range = seekBar.getMax();
                double idle = range / (double) 2.0;
                double control = 2 * ((double) progress - idle) / range;

                command_pitch = control;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                double idle = seekBar.getMax() / (double) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        yaw_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                double range = seekBar.getMax();
                double idle = range / (double) 2.0;
                double control = 2 * ((double) progress - idle) / range;

                command_yaw = control;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                double idle = seekBar.getMax() / (double) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        throttle_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                double range = seekBar.getMax();
                double idle = range / (double) 2.0;
                double control = 2 * ((double) progress - idle) / range;

                command_throttle = control;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                double idle = seekBar.getMax() / (double) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        gimbal_tilt_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                // we assume progress \in [0, seekBar.getMax()]
                double range = seekBar.getMax();
                double idle = range / (double) 2.0;
                double control = 2 * ((double) progress - idle) / range;

                command_gimbal_tilt = control;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                double idle = seekBar.getMax() / (double) 2.0;
                seekBar.setProgress((int) idle);
            }
        });

        actuate_gimbal_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                actuate_gimbal = progress == 1;

                if( ! actuate_gimbal )
                {
                    uiActuator.zero_gimbal_tilt();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

        });

        actuate_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                actuate = progress == 1;

                if( ! actuate )
                {
                    uiActuator.zero_main_controls();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

        });

        virtual_stick_enable_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean human_initiated) {
                enable_virtual_sticks = progress == 1;

                if( human_initiated )
                {
                    fcuConnector.setVirtualSticksEnabled( enable_virtual_sticks );
//                    flight_controller.setVirtualStickModeEnabled(enable_virtual_sticks, new CommonCallbacks.CompletionCallback() {
//                        @Override
//                        public void onResult(DJIError djiError) {
//                            if( null == djiError )
//                            {
//                                showToast(String.format("Successfully set virtual sticks: %b", enable_virtual_sticks));
//                            }
//                            else
//                            {
//                                showToast("Error setting virtual sticks: " + djiError.toString());
//                            }
//                        }
//                    });
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

        });

        // TODO uncomment this and reset
//        if (null == companionBoardConnector) {
//            Thread thread = new Thread(new Runnable() {
//                @Override
//                public void run() {
//                    companionBoardConnector = new CompanionBoardConnector();
//                    companionBoardConnectorTimer = new Timer();
//                    companionBoardConnectorTimer.schedule(companionBoardConnector, 100, 100);
//                }
//            });
//            thread.start();
//        }

        if( null == fcuConnector )
        {
            fcuConnector = new FCUConnector();
            fcuConnectorTimer = new Timer();
            fcuConnectorTimer.schedule(fcuConnector, 100, 100);
        }

        if (null == uiActuator) {
            uiActuator = new UIActuator();
            uiActuatorTimer = new Timer();
            uiActuatorTimer.schedule(uiActuator, 100, 100);
        }

        FlightControllerState.Callback flight_controller_state_callback = new FlightControllerState.Callback() {
            @Override
            public void onUpdate(@NonNull FlightControllerState flightControllerState) {
            }
        };
        ((Aircraft) VideoDecodingApplication.getProductInstance()).getFlightController().setStateCallback(flight_controller_state_callback);



        GimbalState.Callback gimbal_state_callback = new GimbalState.Callback() {
            @Override
            public void onUpdate(@NonNull GimbalState gimbalState) {
                try {

                    double roll = gimbalState.getAttitudeInDegrees().getRoll();
                    double pitch = gimbalState.getAttitudeInDegrees().getPitch();
                    double yaw = gimbalState.getYawRelativeToAircraftHeading();

                    String info_text_string = String.format("RPY: %.2f, %.2f, %.2f", roll, pitch, yaw);
//                    gimbal_info_text.setText(info_text_string);
                }
                catch(Exception e)
                {
                    showToast("Gimbal state callback error: " + e.toString());
                }
            }
        };
        VideoDecodingApplication.getProductInstance().getGimbal().setStateCallback(gimbal_state_callback);

        set_default_modes();

        updateUIVisibility();
    }

    private void set_default_modes() {
        flight_controller.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        flight_controller.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        flight_controller.setVerticalControlMode(VerticalControlMode.VELOCITY);
        flight_controller.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
    }

    private void updateUIVisibility() {
        switch (demoType) {
//            case USE_SURFACE_VIEW:
//                videostreamPreviewSf.setVisibility(View.VISIBLE);
//                videostreamPreviewTtView.setVisibility(View.GONE);
//                break;
//            case USE_SURFACE_VIEW_DEMO_DECODER:
//                /**
//                 * we need display two video stream at the same time, so we need let them to be visible.
//                 */
//                videostreamPreviewSf.setVisibility(View.VISIBLE);
//                videostreamPreviewTtView.setVisibility(View.VISIBLE);
//                break;

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
//            updateTitle(product.getModel().name() + " Connected " + demoType.name());
            updateTitle(String.format("Autonomous Landing Demo: %s", product.getModel().getDisplayName()));
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
                            showToast("can't change mode of camera, error:" + djiError.getDescription());
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
                if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null)
                {
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

                // TODO uncomment and reset
//                companionBoardConnector.set_image_to_send(videostreamPreviewTtView.getBitmap(854, 480));
//                connectCompanionBoardTask.set_image_to_send(videostreamPreviewTtView.getBitmap());
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
    private void oldSaveYuvDataToJPEG(byte[] yuvFrame, int width, int height) {
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

    private void newSaveYuvDataToJPEG(byte[] yuvFrame, int width, int height) {
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
        screenShot(yuvFrame, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", width, height);
    }

    private void newSaveYuvDataToJPEG420P(byte[] yuvFrame, int width, int height) {
        if (yuvFrame.length < width * height) {
            return;
        }
        int length = width * height;

        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];

        for (int i = 0; i < u.length; i++) {
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

            default:
                break;
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

    private class FCUConnector extends TimerTask {

        long last_virtual_stick_send_time_millis;

        public FCUConnector()
        {
            super();
            last_virtual_stick_send_time_millis = System.currentTimeMillis();
        }

        @Override
        public void run()
        {
            if( actuate_gimbal )
            {
                try {
                    VideoDecodingApplication.getProductInstance().getGimbal().rotate(new Rotation.Builder().pitch( 100 * (float) command_gimbal_tilt)
                            .mode(RotationMode.SPEED)
                            .yaw(Rotation.NO_ROTATION)
                            .roll(Rotation.NO_ROTATION)
                            .time(0)
                            .build(), new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {

                        }
                    });
                } catch (Exception e) {
                    showToast("Gimbal actuation error: " + e.toString());
                }
            }

            if( enable_virtual_sticks && (System.currentTimeMillis() - last_virtual_stick_send_time_millis < 250 || command_roll != 0 || command_pitch != 0 || command_yaw != 0 || command_throttle != 0))
            {
                last_virtual_stick_send_time_millis = System.currentTimeMillis();

                try {
                    send_virtual_stick_command(command_roll, command_pitch, command_yaw, command_throttle);
                }
                catch (Exception e)
                {
                    showToast("Errored out while sending virtual stick command: " + e.toString());
                }
            }
        }

        public void send_virtual_stick_command( double roll, double pitch, double yaw, double throttle )
        {
            float command_roll_float     = (float) command_roll;
            float command_pitch_float    = (float) command_pitch;
            float command_yaw_float      = (float) command_yaw * 100;
            float command_throttle_float = (float) command_throttle;


            flight_controller.sendVirtualStickFlightControlData(new FlightControlData(command_roll_float, command_pitch_float, command_yaw_float, command_throttle_float), new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (null != djiError) {
//                        showToast("Error sending virtual stick command: " + djiError.toString());
                    }
                }
            });
        }

        public void setVirtualSticksEnabled( boolean enabled )
        {
            showToast(String.format("Attempting to set virtual sticks enabled to %b", enabled));

            flight_controller.setVirtualStickModeEnabled(enabled, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
//                    showToast("Error setting virtual sticks: " + djiError.toString());
                }
            });
        }

    }

    // task to update the sliders to visualize the desired control outputs
    private class UIActuator extends TimerTask {

        private int control_to_progress(float control, float control_min, float control_max, int progress_min, int progress_max) {
            int progress = (int) (progress_min + (control - control_min) * (progress_max - progress_min) / (control_max - control_min));

            return progress;
        }

        private double linear_interpolate( double input, double input_min, double input_max, double output_min, double output_max)
        {
            return output_min + (input - input_min) * (output_max - output_min) / (input_max - input_min);
        }

        private int constrain(int progress, int max) {
            int result = progress;
            if (result < 0) {
                result = 0;
            } else if (result > max) {
                result = max;
            }

            return result;
        }

        private int constrain(int progress, SeekBar seekbar) {
            int result = progress;

            if (result < 0) {
                result = 0;
            } else if (result > seekbar.getMax()) {
                result = seekbar.getMax();
            }

            return result;
        }

        private double constrain( double input, double min, double max )
        {
            double result = input;
            if( result < min )
            {
                result = min;
            }
            if( result > max )
            {
                result = max;
            }

            return result;
        }

        @Override
        public void run()
        {

            try {
                gimbal_tilt_text.setText(String.format("Gimbal Tilt: %.2f", command_gimbal_tilt));
                yaw_text.setText(String.format("Yaw: %.2f", command_yaw));
                pitch_text.setText(String.format("Pitch: %.2f", command_pitch));
                roll_text.setText(String.format("Roll: %.2f", command_roll));
                throttle_text.setText(String.format("Throttle: %.2f", command_throttle));
            }catch(Exception e)
            {
                showToast("UI Actuator error: " + e.toString());
            }

            if( actuate )
            {
                actuate_controls();
            }

            if( actuate_gimbal )
            {
                actuate_gimbal_tilt();
            }
        }

        public void actuate_controls()
        {
            roll_seekbar.setProgress( (int) constrain(linear_interpolate(command_roll, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
            pitch_seekbar.setProgress( (int) constrain(linear_interpolate(command_pitch, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
            yaw_seekbar.setProgress( (int) constrain(linear_interpolate(command_yaw, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
            throttle_seekbar.setProgress( (int) constrain(linear_interpolate(command_throttle, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
        }

        public void actuate_gimbal_tilt()
        {
            gimbal_tilt_seekbar.setProgress( (int) constrain(linear_interpolate(command_gimbal_tilt, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
        }

        public void zero_main_controls()
        {
            roll_seekbar.setProgress( (int) constrain(linear_interpolate(0, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
            pitch_seekbar.setProgress( (int) constrain(linear_interpolate(0, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
            yaw_seekbar.setProgress( (int) constrain(linear_interpolate(0, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
            throttle_seekbar.setProgress( (int) constrain(linear_interpolate(0, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
        }

        public void zero_gimbal_tilt()
        {
            gimbal_tilt_seekbar.setProgress( (int) constrain(linear_interpolate(0.0, -1.0, 1.0, 0.0, 1000.0), 0, 1000));
        }
    }

    private class CompanionBoardConnector extends TimerTask {

        long last_send_time;
        long last_command_time;

        DatagramSocket udp_image_socket = null;

        private boolean is_connected = false;

        private Bitmap image_to_send = null;
        private String input_string = null;
        InetAddress address;

        CompanionBoardConnector()
        {
            super();
        }

        @Override
        public void run() {

            // make sure the port is open
            try
            {
                udp_image_socket = new DatagramSocket();
                address = InetAddress.getByName("gcs.local");
                udp_image_socket.setSoTimeout(1500);

                last_send_time = System.currentTimeMillis();
            }
            catch(Exception e)
            {
                showToast("Companion board error: " + e.toString());
            }

            // send a request (image) and get a response (commands)
            if( null != image_to_send ) {
                try {
//                    showToast(String.valueOf(image_to_send.getHeight()) + " " + String.valueOf(image_to_send.getWidth()));
                    // send image here
                    Bitmap image_to_send_copy = image_to_send.copy(image_to_send.getConfig(), false);

                    image_to_send = null;
                    Bitmap bitmap_image = toGrayScale(image_to_send_copy);
//                    showToast(String.valueOf(bitmap_image.getHeight()) + " " + String.valueOf(bitmap_image.getWidth()));
                    ByteArrayOutputStream byte_stream = new ByteArrayOutputStream();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//                        bitmap_image.compress(Bitmap.CompressFormat.WEBP_LOSSY, 10, byte_stream);
                        bitmap_image.compress(Bitmap.CompressFormat.JPEG, 50, byte_stream);
                    }
                    byte[] image_byte_array = byte_stream.toByteArray();
                    DatagramPacket packet = new DatagramPacket( image_byte_array, image_byte_array.length, address, 14555);
                    udp_image_socket.send(packet);

                    udp_image_socket.receive(packet);
                    String received = new String(packet.getData(), 0, packet.getLength());
                    char start = received.charAt(0);
                    char end   = received.charAt(received.length() - 1);
                    if( start == '@' && end == '&' )
                    {
                        try
                        {
                            String[] commands = received.split(",");

                            command_yaw         = Double.valueOf(commands[3]);
                            command_gimbal_tilt = Double.valueOf(commands[4]);
                            command_pitch       = Double.valueOf(commands[5]);
                            command_roll        = Double.valueOf(commands[6]);
                            command_throttle    = Double.valueOf(commands[7]);

                            last_command_received_time = System.currentTimeMillis();
                        }
                        catch( Exception e )
                        {
                            command_roll = 0;
                            command_pitch = 0;
                            command_yaw = 0;
                            command_throttle = 0;
                            command_gimbal_tilt = 0;
                            showToast("UNRECOGNIZED COMMAND!" + e.toString());
                        }
                    }
                    else if( received.equals("HEARTBEAT") )
                    {
                        last_command_received_time = System.currentTimeMillis();
                    }
                    else
                    {
                        showToast("command not recognized: " + start + ", " + end);
                    }
                }
                catch (Exception e)
                {
                    showToast("Image send error: " + e.toString());
                }
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
            if( null == image_to_send )
            {
                this.image_to_send = bitmap_image;
            }
        }
    }
}

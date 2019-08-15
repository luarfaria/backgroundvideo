package io.iclue.backgroundvideo;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.hardware.Camera.PreviewCallback;
import android.graphics.YuvImage;
import java.io.IOException;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import java.io.File;
import java.io.FileOutputStream;
import android.graphics.Bitmap;
import android.view.SurfaceHolder;

@SuppressWarnings("deprecation")
public class VideoOverlay extends ViewGroup implements TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    private static final String TAG = "BACKGROUND_VID_OVERLAY";
    private RecordingState mRecordingState = RecordingState.INITIALIZING;
    private int mCameraId = CameraHelper.NO_CAMERA;
    private Camera mCamera = null;
    private TextureView mPreview;
    private boolean mPreviewAttached = false;
    private MediaRecorder mRecorder = null;
    private boolean mStartWhenInitialized = false;
    private SurfaceHolder mHolder;

    private String mFilePath;
    private boolean mRecordAudio = true;
    private int mCameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int mOrientation;
    private int videoBitrate;
    private int audioBitrate;
    private int i = 0;

    public VideoOverlay(Context context) {
        super(context);

        this.setClickable(false);
        this.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Create surface to display the camera preview
        mPreview = new TextureView(getContext());
        mPreview.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPreview.setClickable(false);
        mPreview.setSurfaceTextureListener(this);
        mHolder = getHolder();
		mHolder.addCallback(this);
        attachView();
    }

    public void setCameraFacing(String cameraFace) {
        mCameraFacing = (cameraFace.equalsIgnoreCase("FRONT") ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
    }

    public void setRecordAudio(boolean recordAudio) {
        mRecordAudio = recordAudio;
    }

    public void setVideoBitrate(int videoBitrate) {
        this.videoBitrate = videoBitrate;
    }

    public void setAudioBitrate(int audioBitrate) {
        this.audioBitrate = audioBitrate;
    }

    public void Start(String filePath) throws Exception {
        Log.d(TAG, "Start(String filePath)");
        if (this.mRecordingState == RecordingState.STARTED) {
            Log.w(TAG, "Already Recording");
            return;
        }

        if (!TextUtils.isEmpty(filePath)) {
            this.mFilePath = filePath;
        }
        Log.d(TAG, "attachView()");
        attachView();

        if (this.mRecordingState == RecordingState.INITIALIZING) {
            Log.d(TAG, "mRecordingState == RecordingState.INITIALIZING : return");
            this.mStartWhenInitialized = true;
            return;
        }

        if (TextUtils.isEmpty(mFilePath)) {
            throw new IllegalArgumentException("Filename for recording must be set");
        }

        initializeCamera();

        if (mCamera == null) {
            this.detachView();
            throw new NullPointerException("Cannot start recording, we don't have a camera!");
        }

        // Set camera parameters
        Camera.Parameters cameraParameters = mCamera.getParameters();
        Log.d(TAG, "stopPreview()");
        mCamera.stopPreview(); //Apparently helps with freezing issue on some Samsung devices.
        mCamera.unlock();

        try {
            Log.d(TAG, "new MediaRecorder()");
            mRecorder = new MediaRecorder();
            mRecorder.setCamera(mCamera);

            CamcorderProfile profile;
            if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_LOW)) {
                profile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_LOW);
            } else {
                profile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_HIGH);
            }

            Camera.Size lowestRes = CameraHelper.getLowestResolution(cameraParameters);
            Log.d(TAG, "getLowestResolution: " + lowestRes.width + "x" + lowestRes.height);
            profile.videoFrameWidth = lowestRes.width;
            profile.videoFrameHeight = lowestRes.height;
            Log.d(TAG, profile.videoFrameWidth + "x" + profile.videoFrameHeight);

            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            if (mRecordAudio) {
                // With audio
                mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            }
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setVideoFrameRate(profile.videoFrameRate);
            mRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
            mRecorder.setVideoEncodingBitRate(videoBitrate);
            if (mRecordAudio) {
                mRecorder.setAudioEncodingBitRate(audioBitrate);
                mRecorder.setAudioChannels(profile.audioChannels);
                mRecorder.setAudioSamplingRate(profile.audioSampleRate);
            }
            mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (mRecordAudio) {
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }

            mRecorder.setOutputFile(filePath);
            mRecorder.setOrientationHint(mOrientation);
            mRecorder.prepare();
            Log.d(TAG, "Starting recording");
            mRecorder.start();
            Log.d(TAG, "Started recording");
            this.mRecordingState = RecordingState.STARTED;
            Log.d(TAG, "mRecordingState: " + mRecordingState);
        } catch (Exception e) {
            this.releaseCamera();
            Log.e(TAG, "Could not start recording! MediaRecorder Error", e);
            throw e;
        }
    }

    public String Stop() throws IOException {
        Log.d(TAG, "stopRecording called");

        if (mRecorder != null) {
            MediaRecorder tempRecorder = mRecorder;
            mRecorder = null;
            try {
                tempRecorder.stop();
            } catch (Exception e) {
                //This can occur when the camera failed to start and then stop is called
                Log.e(TAG, "Could not stop recording.", e);
            }
        }

        this.releaseCamera();
        this.detachView();        
        return this.mFilePath;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int numChildren = getChildCount();
        if (changed && numChildren > 0) {
            int itemWidth = (r - l) / numChildren;
            for (int i = 0; i < numChildren; i++) {
                View v = getChildAt(i);
                v.layout(itemWidth * i, 0, (i + 1) * itemWidth, b - t);
            }
        }
    }

    private void initializeCamera() {
        Log.d(TAG, "initializeCamera()");
        if (mCamera == null) {
            try {
                mCameraId = CameraHelper.getCameraId(mCameraFacing);
                if (mCameraId != CameraHelper.NO_CAMERA) {
                    mCamera = Camera.open(mCameraId);
                    Log.d(TAG, "Camera opened: " + mCameraId);
                    // Set camera parameters
                    mOrientation = CameraHelper.calculateOrientation((Activity) this.getContext(), mCameraId);
                    Camera.Parameters cameraParameters = mCamera.getParameters();
                    Camera.Size previewSize = CameraHelper.getPreviewSize(cameraParameters);
//                    Camera.Size previewSize = CameraHelper.getLowestResolution(cameraParameters);
                    cameraParameters.setPreviewSize(previewSize.width, previewSize.height);
                    Log.d(TAG, "setPreviewSize: " + previewSize.width + "x" + previewSize.height);
                    cameraParameters.setRotation(mOrientation);
                    cameraParameters.setRecordingHint(true);

                    mCamera.setParameters(cameraParameters);
                    mCamera.setDisplayOrientation(mOrientation);
                    mCamera.setErrorCallback(new Camera.ErrorCallback() {
                        @Override
                        public void onError(int error, Camera camera) {
                            Log.e(TAG, "Camera error: " + error);
                        }
                    });
                    Log.d(TAG, "Camera configured");
                }
            } catch (RuntimeException ex) {
                this.releaseCamera();
                Log.e(TAG, "Unable to open camera. Another application probably has a lock", ex);
            }
        }
    }

    private void releaseCamera() {
        Log.d(TAG, "releaseCamera()");
        if (mRecorder != null) {
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.lock();
            mCamera.release();
            mCamera = null;
            mCameraId = CameraHelper.NO_CAMERA;
        }
        this.mRecordingState = RecordingState.STOPPED;
        Log.d(TAG, "mRecordingState: " + mRecordingState);
    }

    private void attachView() {
        Log.d(TAG, "attachView()");
        if (!mPreviewAttached && mPreview != null) {
            Log.d(TAG, "addView(mPreview)");
            this.addView(mPreview);
            this.mPreviewAttached = true;
            Log.d(TAG, "attachView() attached");
        }
    }

    private void detachView() {
        Log.d(TAG, "detachView()");
        if (mPreviewAttached && mPreview != null) {
            Log.d(TAG, "removeView(mPreview)");
            this.removeView(mPreview);
            this.mPreviewAttached = false;
            this.mRecordingState = RecordingState.INITIALIZING;
            Log.d(TAG, "mRecordingState: " + mRecordingState);
        }
    }    
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "Creating Texture Created");
        Log.d(TAG, "mRecordingState: " + mRecordingState);

        initializeCamera();

        if (mCamera != null && this.mRecordingState != RecordingState.STARTED) {
            try {
                Log.d(TAG, "setPreviewTexture");
                mCamera.setPreviewTexture(surface);       
                
            } catch (IOException e) {
                Log.e(TAG, "Unable to attach preview to camera!", e);
            }
            Log.d(TAG, "startPreview");
            mCamera.startPreview();
        } else {
            if (mCamera == null) {
                Log.e(TAG, "mCamera == null");
            }
        }

        if (this.mRecordingState == RecordingState.INITIALIZING) {
            this.mRecordingState = RecordingState.STOPPED;//INITIALIZING complete
        }
        Log.d(TAG, "mStartWhenInitialized: " + mStartWhenInitialized);
        Log.d(TAG, "this.mRecordingState != RecordingState.STARTED: " + (this.mRecordingState != RecordingState.STARTED));
        if (mStartWhenInitialized && this.mRecordingState != RecordingState.STARTED) {
            Log.d(TAG, "mRecordingState: " + mRecordingState);
            try {
                Log.d(TAG, "mStartWhenInitialized Start(this.mFilePath)");
                Start(this.mFilePath);
            } catch (Exception ex) {
                Log.e(TAG, "Error start camera", ex);
            }
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
         
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
      
    }
    public void surfaceCreated(SurfaceHolder holder) {
		if (mCamera == null) {
			return;
		}
		// The Surface has been created, now tell the camera where to draw the
		// preview.
		try {
			mCamera.setPreviewDisplay(holder);
			mCamera.startPreview();
		} catch (IOException e) {
			Log.d(TAG, "Error setting camera preview: " + e.getMessage());
		}
	}
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        mCamera.setPreviewCallback(new PreviewCallback() {

			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {

				// ***The parameter 'data' holds the frame information***				
				  int width = 0; int height = 0;
				 
				 Camera.Parameters parameters = camera.getParameters();
				 
				 height = parameters.getPreviewSize().height;
				 
				 width = parameters.getPreviewSize().width;
				 

				// ****You can change formats, save the data
				// to file etc.*****
				
				 YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21,
				 width, height, null);
				 i++;
				 Rect rectangle = new Rect(0, 0, width, height);	
                try{
                File file = new File(mFilePath.replace(".mp4", Integer.toString(i) + ".jpg"));
                FileOutputStream output = new FileOutputStream(file);
				 yuvImage.compressToJpeg(rectangle, 100, output);
				    output.flush();
                  output.close();
                }
                catch (Exception e){
                     Log.e(TAG, "Unable to attach preview to camera!", e);
                }
			}

		});
    }

	public void surfaceDestroyed(SurfaceHolder holder) {
		// empty. Take care of releasing the Camera preview in your activity.        
	}

    private enum RecordingState {INITIALIZING, STARTED, STOPPED}
}

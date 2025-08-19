package com.johnsonsu.rnsoundplayer;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import javax.annotation.Nullable;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.LifecycleEventListener;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.ContentDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.C;

public class RNSoundPlayerModule extends ReactContextBaseJavaModule implements LifecycleEventListener {

  public final static String EVENT_SETUP_ERROR = "OnSetupError";
  public final static String EVENT_FINISHED_PLAYING = "FinishedPlaying";
  public final static String EVENT_FINISHED_LOADING = "FinishedLoading";
  public final static String EVENT_FINISHED_LOADING_FILE = "FinishedLoadingFile";
  public final static String EVENT_FINISHED_LOADING_URL = "FinishedLoadingURL";
  public final static String EVENT_CHUNK_RECEIVED = "OnChunkReceived";

  private final ReactApplicationContext reactContext;
  private ExoPlayer exoPlayer;
  private float volume;
  private AudioManager audioManager;
  private boolean isStreaming = false;

  public RNSoundPlayerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
    this.volume = 1.0f;
    this.audioManager = (AudioManager) this.reactContext.getSystemService(Context.AUDIO_SERVICE);
    reactContext.addLifecycleEventListener(this);
  }

  @Override
  public String getName() {
    return "RNSoundPlayer";
  }

  @ReactMethod
  public void setSpeaker(Boolean on) {
    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    audioManager.setSpeakerphoneOn(on);
  }

  @Override
  public void onHostResume() {
  }

  @Override
  public void onHostPause() {
  }

  @Override
  public void onHostDestroy() {
    this.stop();
    if (exoPlayer != null) {
      exoPlayer.release();
      exoPlayer = null;
    }
  }

  @ReactMethod
  public void playSoundFile(String name, String type) throws IOException {
    mountSoundFile(name, type);
    this.resume();
  }

  @ReactMethod
  public void loadSoundFile(String name, String type) throws IOException {
    mountSoundFile(name, type);
  }

  @ReactMethod
  public void playUrl(String url) throws IOException {
    prepareUrl(url);
    this.resume();
  }

  @ReactMethod
  public void loadUrl(String url) throws IOException {
    prepareUrl(url);
  }

  @ReactMethod
  public void playUrlWithStreaming(String url) throws IOException {
    prepareUrlWithStreaming(url);
    this.resume();
  }

  @ReactMethod
  public void loadUrlWithStreaming(String url) throws IOException {
    prepareUrlWithStreaming(url);
  }

  @ReactMethod
  public void pause() throws IllegalStateException {
    if (this.exoPlayer != null) {
      this.exoPlayer.pause();
    }
  }

  @ReactMethod
  public void resume() throws IOException, IllegalStateException {
    if (this.exoPlayer != null) {
      this.setVolume(this.volume);
      this.exoPlayer.play();
    }
  }

  @ReactMethod
  public void stop() throws IllegalStateException {
    if (this.exoPlayer != null) {
      this.exoPlayer.stop();
    }
  }

  @ReactMethod
  public void seek(float seconds) throws IllegalStateException {
    if (this.exoPlayer != null) {
      long positionMs = (long) (seconds * 1000);
      this.exoPlayer.seekTo(positionMs);
    }
  }

  @ReactMethod
  public void setVolume(float volume) throws IOException {
    this.volume = volume;
    if (this.exoPlayer != null) {
      this.exoPlayer.setVolume(volume);
    }
  }

  @ReactMethod
  public void setNumberOfLoops(int noOfLooping){
    if (this.exoPlayer != null) {
      if (noOfLooping == 0) {
        this.exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
      } else {
        this.exoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
      }
    }
  }

  @ReactMethod
  public void getInfo(Promise promise) {
    if (this.exoPlayer == null) {
      promise.resolve(null);
      return;
    }
    WritableMap map = Arguments.createMap();
    map.putDouble("currentTime", this.exoPlayer.getCurrentPosition() / 1000.0);
    map.putDouble("duration", this.exoPlayer.getDuration() / 1000.0);
    promise.resolve(map);
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Set up any upstream listeners or background tasks as necessary
  }

  @ReactMethod
  public void removeListeners(Integer count) {
    // Remove upstream listeners, stop unnecessary background tasks
  }

  private void sendEvent(ReactApplicationContext reactContext,
                         String eventName,
                         @Nullable WritableMap params) {
    reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
  }

  private void mountSoundFile(String name, String type) throws IOException {
    try {
      Uri uri;
      int soundResID = getReactApplicationContext().getResources().getIdentifier(name, "raw", getReactApplicationContext().getPackageName());

      if (soundResID > 0) {
        uri = Uri.parse("android.resource://" + getReactApplicationContext().getPackageName() + "/raw/" + name);
      } else {
        uri = this.getUriFromFile(name, type);
      }

      initializeExoPlayer();
      MediaItem mediaItem = MediaItem.fromUri(uri);
      this.exoPlayer.setMediaItem(mediaItem);
      this.exoPlayer.prepare();
      
      sendMountFileSuccessEvents(name, type);
    } catch (Exception e) {
      sendErrorEvent(new IOException(e.getMessage()));
    }
  }

  private Uri getUriFromFile(String name, String type) {
    String folder = getReactApplicationContext().getFilesDir().getAbsolutePath();
    String file = (!type.isEmpty()) ? name + "." + type : name;

    File ref = new File(folder + "/" + file);

    if (ref.exists()) {
      ref.setReadable(true, false);
    }

    return Uri.parse("file://" + folder + "/" + file);
  }

  private void prepareUrl(final String url) throws IOException {
    try {
      initializeExoPlayer();
      
      MediaItem mediaItem = MediaItem.fromUri(url);
      this.exoPlayer.setMediaItem(mediaItem);
      this.exoPlayer.prepare();
      
      WritableMap params = Arguments.createMap();
      params.putBoolean("success", true);
      sendEvent(getReactApplicationContext(), EVENT_FINISHED_LOADING, params);
      
      WritableMap onFinishedLoadingURLParams = Arguments.createMap();
      onFinishedLoadingURLParams.putBoolean("success", true);
      onFinishedLoadingURLParams.putString("url", url);
      sendEvent(getReactApplicationContext(), EVENT_FINISHED_LOADING_URL, onFinishedLoadingURLParams);
    } catch (Exception e) {
      WritableMap errorParams = Arguments.createMap();
      errorParams.putString("error", e.getMessage());
      sendEvent(getReactApplicationContext(), EVENT_SETUP_ERROR, errorParams);
    }
  }

  private void prepareUrlWithStreaming(final String url) throws IOException {
    try {
      initializeExoPlayer();
      this.isStreaming = true;
      
      // Create a custom data source factory for streaming with chunk processing
      StreamingDataSource.Factory dataSourceFactory = new StreamingDataSource.Factory(url, getReactApplicationContext());
      
      MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
              .createMediaSource(MediaItem.fromUri(url));
      
      this.exoPlayer.setMediaSource(mediaSource);
      this.exoPlayer.prepare();
      
      WritableMap params = Arguments.createMap();
      params.putBoolean("success", true);
      sendEvent(getReactApplicationContext(), EVENT_FINISHED_LOADING, params);
      
      WritableMap onFinishedLoadingURLParams = Arguments.createMap();
      onFinishedLoadingURLParams.putBoolean("success", true);
      onFinishedLoadingURLParams.putString("url", url);
      sendEvent(getReactApplicationContext(), EVENT_FINISHED_LOADING_URL, onFinishedLoadingURLParams);
    } catch (Exception e) {
      WritableMap errorParams = Arguments.createMap();
      errorParams.putString("error", e.getMessage());
      sendEvent(getReactApplicationContext(), EVENT_SETUP_ERROR, errorParams);
    }
  }

  private void initializeExoPlayer() {
    if (this.exoPlayer == null) {
      this.exoPlayer = new ExoPlayer.Builder(getReactApplicationContext()).build();
      
      // Set audio attributes
      AudioAttributes audioAttributes = new AudioAttributes.Builder()
              .setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
              .build();
      this.exoPlayer.setAudioAttributes(audioAttributes, true);
      
      // Add listeners
      this.exoPlayer.addListener(new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
          if (playbackState == Player.STATE_ENDED) {
            WritableMap params = Arguments.createMap();
            params.putBoolean("success", true);
            sendEvent(getReactApplicationContext(), EVENT_FINISHED_PLAYING, params);
          }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
          Log.e("RNSoundPlayer", "ExoPlayer error: " + error.getMessage());
          WritableMap errorParams = Arguments.createMap();
          errorParams.putString("error", error.getMessage());
          sendEvent(getReactApplicationContext(), EVENT_SETUP_ERROR, errorParams);
        }
        
        @Override
        public void onIsLoadingChanged(boolean isLoading) {
          if (isStreaming && !isLoading) {
            // Simulate chunk processing for streaming
            long position = exoPlayer.getCurrentPosition();
            long duration = exoPlayer.getDuration();
            
            WritableMap chunkData = Arguments.createMap();
            chunkData.putDouble("position", position);
            chunkData.putDouble("duration", duration);
            chunkData.putBoolean("isLoading", isLoading);
            sendEvent(getReactApplicationContext(), EVENT_CHUNK_RECEIVED, chunkData);
          }
        }
      });
    }
  }

  private void sendMountFileSuccessEvents(String name, String type) {
    WritableMap params = Arguments.createMap();
    params.putBoolean("success", true);
    sendEvent(reactContext, EVENT_FINISHED_LOADING, params);

    WritableMap onFinishedLoadingFileParams = Arguments.createMap();
    onFinishedLoadingFileParams.putBoolean("success", true);
    onFinishedLoadingFileParams.putString("name", name);
    onFinishedLoadingFileParams.putString("type", type);
    sendEvent(reactContext, EVENT_FINISHED_LOADING_FILE, onFinishedLoadingFileParams);
  }

  private void sendErrorEvent(IOException e) {
    WritableMap errorParams = Arguments.createMap();
    errorParams.putString("error", e.getMessage());
    sendEvent(reactContext, EVENT_SETUP_ERROR, errorParams);
  }

  // Custom DataSource for chunk processing with ExoPlayer
  private static class StreamingDataSource implements DataSource {
    private final String url;
    private final ReactApplicationContext reactContext;
    private HttpURLConnection connection;
    private InputStream inputStream;
    private long bytesRemaining;
    private boolean opened;
    private DataSpec dataSpec;

    public StreamingDataSource(String url, ReactApplicationContext reactContext) {
      this.url = url;
      this.reactContext = reactContext;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
      // Optional: implement transfer listener for progress tracking
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
      this.dataSpec = dataSpec;
      this.opened = true;

      try {
        connection = createConnection();
        
        // Handle range requests for seeking
        if (dataSpec.position != 0) {
          connection.setRequestProperty("Range", "bytes=" + dataSpec.position + "-");
        }
        
        connection.connect();
        
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode > 299) {
          throw new HttpDataSource.HttpDataSourceException(
            "Response code: " + responseCode, 
            dataSpec, 
            HttpDataSource.HttpDataSourceException.TYPE_OPEN
          );
        }
        
        inputStream = connection.getInputStream();
        
        long contentLength = connection.getContentLength();
        bytesRemaining = dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : contentLength;
        
        return bytesRemaining;
      } catch (IOException e) {
        throw new HttpDataSource.HttpDataSourceException(
          "Unable to connect", 
          e, 
          dataSpec, 
          HttpDataSource.HttpDataSourceException.TYPE_OPEN
        );
      }
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSource.HttpDataSourceException {
      if (readLength == 0) {
        return 0;
      }
      
      if (bytesRemaining == 0) {
        return C.RESULT_END_OF_INPUT;
      }

      try {
        int bytesToRead = bytesRemaining != C.LENGTH_UNSET ? 
          (int) Math.min(readLength, bytesRemaining) : readLength;
        
        int bytesRead = inputStream.read(buffer, offset, bytesToRead);
        
        if (bytesRead > 0) {
          if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesRead;
          }
          
          // Process chunk here - similar to original implementation
          processChunk(buffer, bytesRead, offset, dataSpec.position);
        }
        
        return bytesRead;
      } catch (IOException e) {
        throw new HttpDataSource.HttpDataSourceException(
          "Read error", 
          e, 
          dataSpec, 
          HttpDataSource.HttpDataSourceException.TYPE_READ
        );
      }
    }

    @Override
    public Uri getUri() {
      return Uri.parse(url);
    }

    @Override
    public void close() throws HttpDataSource.HttpDataSourceException {
      try {
        if (inputStream != null) {
          inputStream.close();
          inputStream = null;
        }
        if (connection != null) {
          connection.disconnect();
          connection = null;
        }
        opened = false;
      } catch (IOException e) {
        throw new HttpDataSource.HttpDataSourceException(
          "Close error", 
          e, 
          dataSpec, 
          HttpDataSource.HttpDataSourceException.TYPE_CLOSE
        );
      }
    }

    private HttpURLConnection createConnection() throws IOException {
      URL urlObj = new URL(url);
      HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(10000);
      conn.setRequestProperty("User-Agent", "RNSoundPlayer");
      return conn;
    }

    private void processChunk(byte[] buffer, int length, int offset, long position) {
      // Your custom chunk processing logic here
      // For example, you could:
      // - Decrypt the chunk
      // - Apply audio effects
      // - Log streaming progress
      // - Send chunk data to React Native
      
      try {
        WritableMap chunkData = Arguments.createMap();
        chunkData.putInt("chunkSize", length);
        chunkData.putDouble("position", position);
        chunkData.putString("data", android.util.Base64.encodeToString(buffer, offset, length, android.util.Base64.DEFAULT));
        
        reactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit(EVENT_CHUNK_RECEIVED, chunkData);
      } catch (Exception e) {
        Log.e("StreamingDataSource", "Error processing chunk: " + e.getMessage());
      }
    }

    // Factory class for creating StreamingDataSource instances
    public static class Factory implements DataSource.Factory {
      private final String url;
      private final ReactApplicationContext reactContext;

      public Factory(String url, ReactApplicationContext reactContext) {
        this.url = url;
        this.reactContext = reactContext;
      }

      @Override
      public DataSource createDataSource() {
        return new StreamingDataSource(url, reactContext);
      }
    }
  }
}

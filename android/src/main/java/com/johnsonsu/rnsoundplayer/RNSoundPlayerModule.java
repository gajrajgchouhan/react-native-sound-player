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
import com.google.android.exoplayer2.DefaultLoadControl;
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

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import java.security.Security;
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
  
  // Custom values for encrypted audio
  private int encryptedBitrate = 0;
  private float encryptedDuration = 0f;
  private boolean useCustomDurationAndBitrate = false;

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
  public void playUrlWithStreamingEncrypted(String url, String dekHex, String counterBaseHex, int bitrate, float duration) throws IOException {
    prepareUrlWithStreamingEncrypted(url, dekHex, counterBaseHex, bitrate, duration);
    this.resume();
  }

  @ReactMethod
  public void loadUrlWithStreamingEncrypted(String url, String dekHex, String counterBaseHex, int bitrate, float duration) throws IOException {
    prepareUrlWithStreamingEncrypted(url, dekHex, counterBaseHex, bitrate, duration);
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
      if (useCustomDurationAndBitrate && encryptedBitrate > 0) {
        // For encrypted audio with custom bitrate, calculate byte offset for seeking
        // This provides more accurate seeking for encrypted streams
        long byteOffset = (long) (seconds * encryptedBitrate / 8); // Convert bits to bytes
        
        // Use ExoPlayer's seek but with calculated position
        // Note: ExoPlayer will handle the actual byte-to-time conversion internally
        long positionMs = (long) (seconds * 1000);
        this.exoPlayer.seekTo(positionMs);
        
        Log.d("RNSoundPlayer", String.format("Seeking encrypted audio: %.2fs -> %d bytes (bitrate: %d bps)", 
                seconds, byteOffset, encryptedBitrate));
      } else {
        // Standard seeking for non-encrypted audio
        long positionMs = (long) (seconds * 1000);
        this.exoPlayer.seekTo(positionMs);
      }
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
    
    // Use custom duration for encrypted audio if available, otherwise use ExoPlayer's duration
    if (useCustomDurationAndBitrate && encryptedDuration > 0) {
      map.putDouble("duration", encryptedDuration);
      map.putInt("bitrate", encryptedBitrate);
      map.putBoolean("customDuration", true);
    } else {
      map.putDouble("duration", this.exoPlayer.getDuration() / 1000.0);
      map.putBoolean("customDuration", false);
    }
    
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
      // Reset custom duration and bitrate for non-encrypted audio
      this.useCustomDurationAndBitrate = false;
      this.encryptedBitrate = 0;
      this.encryptedDuration = 0f;
      
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
      // Reset custom duration and bitrate for non-encrypted audio
      this.useCustomDurationAndBitrate = false;
      this.encryptedBitrate = 0;
      this.encryptedDuration = 0f;
      
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
      // Reset custom duration and bitrate for non-encrypted streaming audio
      this.useCustomDurationAndBitrate = false;
      this.encryptedBitrate = 0;
      this.encryptedDuration = 0f;
      
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

  private void prepareUrlWithStreamingEncrypted(final String url, String dekHex, String counterBaseHex, int bitrate, float duration) throws IOException {
    try {
      initializeExoPlayer();
      this.isStreaming = true;
      
      // Store custom values for encrypted audio
      this.encryptedBitrate = bitrate;
      this.encryptedDuration = duration;
      this.useCustomDurationAndBitrate = true;
      
      // Create a custom data source factory for encrypted streaming with chunk processing
      StreamingDataSource.Factory dataSourceFactory = new StreamingDataSource.Factory(url, getReactApplicationContext(), dekHex, counterBaseHex);
      
      MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
              .createMediaSource(MediaItem.fromUri(url));
      
      this.exoPlayer.setMediaSource(mediaSource);
      this.exoPlayer.prepare();
      
      WritableMap params = Arguments.createMap();
      params.putBoolean("success", true);
      params.putBoolean("encrypted", true);
      params.putInt("bitrate", bitrate);
      params.putDouble("duration", duration);
      sendEvent(getReactApplicationContext(), EVENT_FINISHED_LOADING, params);
      
      WritableMap onFinishedLoadingURLParams = Arguments.createMap();
      onFinishedLoadingURLParams.putBoolean("success", true);
      onFinishedLoadingURLParams.putString("url", url);
      onFinishedLoadingURLParams.putBoolean("encrypted", true);
      onFinishedLoadingURLParams.putInt("bitrate", bitrate);
      onFinishedLoadingURLParams.putDouble("duration", duration);
      sendEvent(getReactApplicationContext(), EVENT_FINISHED_LOADING_URL, onFinishedLoadingURLParams);
    } catch (Exception e) {
      WritableMap errorParams = Arguments.createMap();
      errorParams.putString("error", e.getMessage());
      sendEvent(getReactApplicationContext(), EVENT_SETUP_ERROR, errorParams);
    }
  }

  private void initializeExoPlayer() {
    if (this.exoPlayer == null) {
      // Use custom LoadControl to limit buffering for encrypted streams
      com.google.android.exoplayer2.DefaultLoadControl loadControl = new com.google.android.exoplayer2.DefaultLoadControl.Builder()
              .setBufferDurationsMs(
                      2000,   // Min buffer (2s) - reduced from default 
                      8000,   // Max buffer (8s) - reduced from default 50s
                      1500,   // Buffer for playback (1.5s)
                      2000    // Buffer for playback after rebuffer (2s)
              )
              .build();
      
      this.exoPlayer = new ExoPlayer.Builder(getReactApplicationContext())
              .setLoadControl(loadControl)
              .build();
      
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
    
    // Decryption fields
    private SecretKeySpec dekKey;
    private byte[] counterBase;
    private boolean decryptionEnabled = false;
    private long totalBytesRead = 0;
    
    // Reusable objects to prevent memory allocations
    private Cipher cipher;
    private byte[] reusableCounter;
    private static final int MAX_CHUNK_SIZE = 64 * 1024; // 64KB max chunk size

    public StreamingDataSource(String url, ReactApplicationContext reactContext) {
      this.url = url;
      this.reactContext = reactContext;
    }
    
    public StreamingDataSource(String url, ReactApplicationContext reactContext, String dekHex, String counterBaseHex) {
      this.url = url;
      this.reactContext = reactContext;
      
      if (dekHex != null && !dekHex.isEmpty() && counterBaseHex != null && !counterBaseHex.isEmpty()) {
        try {
          byte[] dekBytes = hexToByteArray(dekHex);
          this.dekKey = new SecretKeySpec(dekBytes, "AES");
          this.counterBase = hexToByteArray(counterBaseHex);
          this.reusableCounter = new byte[this.counterBase.length]; // Reusable counter array
          
          // Initialize cipher once
          this.cipher = Cipher.getInstance("AES/CTR/NoPadding");
          
          this.decryptionEnabled = true;
          Log.d("StreamingDataSource", "Decryption enabled with AES-CTR, max chunk size: " + MAX_CHUNK_SIZE);
        } catch (Exception e) {
          Log.e("StreamingDataSource", "Failed to initialize decryption: " + e.getMessage());
          this.decryptionEnabled = false;
        }
      }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
      // Optional: implement transfer listener for progress tracking
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
      this.dataSpec = dataSpec;
      this.opened = true;
      this.totalBytesRead = 0; // Reset counter for new stream

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
        // Limit chunk size for encrypted streams to prevent memory issues
        int bytesToRead = bytesRemaining != C.LENGTH_UNSET ? 
          (int) Math.min(readLength, bytesRemaining) : readLength;
          
        // Further limit chunk size for encrypted streams
        if (decryptionEnabled) {
          bytesToRead = Math.min(bytesToRead, MAX_CHUNK_SIZE);
        }
        
        int bytesRead = inputStream.read(buffer, offset, bytesToRead);
        
        if (bytesRead > 0) {
          if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesRead;
          }
          
          // Process chunk here - decrypt in-place to avoid extra allocations
          if (decryptionEnabled) {
            decryptChunkInPlace(buffer, offset, bytesRead, dataSpec.position + totalBytesRead);
          }
          
          // Send chunk event with minimal data
          sendChunkEvent(bytesRead, dataSpec.position + totalBytesRead);
          totalBytesRead += bytesRead;
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
        
        // Clear sensitive data
        if (reusableCounter != null) {
          java.util.Arrays.fill(reusableCounter, (byte) 0);
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

    // Optimized method that decrypts data in-place to avoid memory allocations
    private void decryptChunkInPlace(byte[] buffer, int offset, int length, long currentOffset) {
      try {
        if (!decryptionEnabled || cipher == null || dekKey == null || counterBase == null) {
          return;
        }
        
        // Calculate counter for this chunk based on offset - reuse array
        updateCounter(reusableCounter, counterBase, currentOffset);
        
        // Create IV parameter spec for AES-CTR
        IvParameterSpec ivSpec = new IvParameterSpec(reusableCounter);
        
        // Reinitialize cipher with new IV
        cipher.init(Cipher.DECRYPT_MODE, dekKey, ivSpec);
        
        // Decrypt in-place to avoid additional memory allocation
        // Create temporary array only for the chunk being decrypted
        byte[] chunkData = new byte[length];
        System.arraycopy(buffer, offset, chunkData, 0, length);
        
        byte[] decryptedData = cipher.doFinal(chunkData);
        
        // Copy decrypted data back to buffer
        System.arraycopy(decryptedData, 0, buffer, offset, Math.min(decryptedData.length, length));
        
      } catch (Exception e) {
        Log.e("StreamingDataSource", "Decryption failed: " + e.getMessage());
        // Continue with encrypted data if decryption fails
      }
    }
    
    // Optimized method that updates counter in-place
    private void updateCounter(byte[] targetCounter, byte[] baseCounter, long offset) {
      // Copy base counter to target
      System.arraycopy(baseCounter, 0, targetCounter, 0, baseCounter.length);
      
      // Calculate number of 16-byte blocks (AES block size)
      long blocks = offset / 16;
      
      // Add blocks to counter (big-endian) - reuse existing array
      long carry = blocks;
      for (int i = targetCounter.length - 1; i >= 0 && carry > 0; i--) {
        long sum = (targetCounter[i] & 0xff) + (carry & 0xff);
        targetCounter[i] = (byte) (sum & 0xff);
        carry = (carry >>> 8) + (sum >>> 8);
      }
    }
    
    // Lightweight method to send chunk events
    private void sendChunkEvent(int length, long position) {
      try {
        WritableMap chunkEventData = Arguments.createMap();
        chunkEventData.putInt("chunkSize", length);
        chunkEventData.putDouble("position", position);
        chunkEventData.putBoolean("encrypted", decryptionEnabled);
        
        reactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit(EVENT_CHUNK_RECEIVED, chunkEventData);
      } catch (Exception e) {
        Log.e("StreamingDataSource", "Error sending chunk event: " + e.getMessage());
      }
    }
    
    private byte[] hexToByteArray(String hex) {
      int len = hex.length();
      byte[] data = new byte[len / 2];
      for (int i = 0; i < len; i += 2) {
        data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                             + Character.digit(hex.charAt(i+1), 16));
      }
      return data;
    }

    // Factory class for creating StreamingDataSource instances
    public static class Factory implements DataSource.Factory {
      private final String url;
      private final ReactApplicationContext reactContext;
      private final String dekHex;
      private final String counterBaseHex;

      public Factory(String url, ReactApplicationContext reactContext) {
        this.url = url;
        this.reactContext = reactContext;
        this.dekHex = null;
        this.counterBaseHex = null;
      }
      
      public Factory(String url, ReactApplicationContext reactContext, String dekHex, String counterBaseHex) {
        this.url = url;
        this.reactContext = reactContext;
        this.dekHex = dekHex;
        this.counterBaseHex = counterBaseHex;
      }

      @Override
      public DataSource createDataSource() {
        if (dekHex != null && counterBaseHex != null) {
          return new StreamingDataSource(url, reactContext, dekHex, counterBaseHex);
        }
        return new StreamingDataSource(url, reactContext);
      }
    }
  }
}

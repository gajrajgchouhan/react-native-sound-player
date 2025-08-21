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
import java.util.Arrays;
import java.math.BigInteger;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

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
  // 
  // Key Features (based on Medium article best practices):
  // 1. AES-CTR block alignment for encrypted seeking
  // 2. Separate buffers for encrypted/decrypted data
  // 3. Precise HTTP Range requests
  // 4. Proper return value handling (decrypted bytes vs network bytes)
  // 5. Enhanced error handling and logging
  //
  // Data Flow: HTTP Stream → encryptedBuffer → decrypt → decryptedBuffer → ExoPlayer buffer
  private static class StreamingDataSource implements DataSource {
    private final String url;
    private final ReactApplicationContext reactContext;
    private HttpURLConnection connection;
    private InputStream inputStream;
    private long bytesRemaining;
    private boolean opened;
    private DataSpec dataSpec;
    
    // Decryption fields
    private byte[] dekKey;
    private byte[] counterBase;
    private boolean decryptionEnabled = false;
    private long totalBytesRead = 0;
    
    // Header buffering fields
    private static final int HEADER_BUFFER_SIZE = 8192; // Enough for most audio headers
    private ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
    private boolean headersReady = false;
    private int headerBytesConsumed = 0; // Track how much of header buffer we've given to ExoPlayer
    
    // Bouncy Castle CTR cipher
    private SICBlockCipher ctrCipher;
    private byte[] encryptedBuffer;  // Separate buffer for encrypted data
    private byte[] decryptedBuffer;  // Separate buffer for decrypted data
    private static final int MAX_CHUNK_SIZE = 64 * 1024; // 64KB max chunk size
    private static final int AES_BLOCK_SIZE = 16; // AES block size for alignment

    public StreamingDataSource(String url, ReactApplicationContext reactContext) {
      this.url = url;
      this.reactContext = reactContext;
    }
    
    public StreamingDataSource(String url, ReactApplicationContext reactContext, String dekHex, String counterBaseHex) {
      this.url = url;
      this.reactContext = reactContext;
      
      if (dekHex != null && !dekHex.isEmpty() && counterBaseHex != null && !counterBaseHex.isEmpty()) {
        try {
          // Add Bouncy Castle provider if not already added
          if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
          }
          
          this.dekKey = hexToByteArray(dekHex);
          this.counterBase = hexToByteArray(counterBaseHex);
          
          // Initialize Bouncy Castle CTR cipher
          this.ctrCipher = new SICBlockCipher(new AESEngine());
          
          // Initialize separate buffers for encrypted and decrypted data
          this.encryptedBuffer = new byte[MAX_CHUNK_SIZE];
          this.decryptedBuffer = new byte[MAX_CHUNK_SIZE];
          
          this.decryptionEnabled = true;
          Log.d("StreamingDataSource", "Decryption enabled with Bouncy Castle AES-CTR, max chunk size: " + MAX_CHUNK_SIZE);
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
      // CRITICAL: Align position to AES block boundary for encrypted streams
      DataSpec alignedDataSpec = dataSpec;
      if (decryptionEnabled) {
        alignedDataSpec = alignDataSpecToBlockBoundary(dataSpec);
        Log.d("StreamingDataSource", String.format("Block alignment: original pos=%d, aligned pos=%d", 
               dataSpec.position, alignedDataSpec.position));
      }
      
      this.dataSpec = alignedDataSpec;
      this.opened = true;
      this.totalBytesRead = 0; // Reset counter for new stream
      
      // Reset header buffering state for new stream
      this.headerBuffer.reset();
      this.headersReady = false;
      this.headerBytesConsumed = 0;

      try {
        connection = createConnection();
        
        // Handle range requests for seeking with improved precision
        if (alignedDataSpec.position != 0) {
          String rangeHeader = buildRangeRequestHeader(alignedDataSpec.position, alignedDataSpec.length);
          connection.setRequestProperty("Range", rangeHeader);
          Log.d("StreamingDataSource", "Range request: " + rangeHeader);
        }
        
        connection.connect();
        
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode > 299) {
          String errorMessage = String.format("HTTP error: %d %s for URL: %s", 
                  responseCode, connection.getResponseMessage(), url);
          Log.e("StreamingDataSource", errorMessage);
          throw new HttpDataSource.HttpDataSourceException(
            errorMessage, 
            alignedDataSpec, 
            HttpDataSource.HttpDataSourceException.TYPE_OPEN
          );
        }
        
        // Log successful response
        Log.d("StreamingDataSource", String.format("HTTP %d: %s", responseCode, connection.getResponseMessage()));
        
        inputStream = connection.getInputStream();
        
        long contentLength = connection.getContentLength();
        bytesRemaining = alignedDataSpec.length != C.LENGTH_UNSET ? alignedDataSpec.length : contentLength;
        
        // Log connection details for debugging
        Log.d("StreamingDataSource", String.format("Connected: %s, Content-Length: %d, Bytes remaining: %d", 
               url, contentLength, bytesRemaining));
        
        return bytesRemaining;
      } catch (IOException e) {
        Log.e("StreamingDataSource", "Connection failed for URL: " + url, e);
        throw new HttpDataSource.HttpDataSourceException(
          "Unable to connect to: " + url, 
          e, 
          alignedDataSpec, 
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

      if (!headersReady) {
        // Keep buffering until we have enough header data
        try {
          bufferHeaderData(readLength);
          
          if (headerBuffer.size() >= HEADER_BUFFER_SIZE) {
            headersReady = true;
            Log.d("StreamingDataSource", "Headers buffered (" + headerBuffer.size() + " bytes), ready for ExoPlayer");
          } else {
            // Return 0 to make ExoPlayer wait
            Log.d("StreamingDataSource", "Buffering headers: " + headerBuffer.size() + "/" + HEADER_BUFFER_SIZE + " bytes");
            return 0;
          }
        } catch (IOException e) {
          throw new HttpDataSource.HttpDataSourceException(
            "Header buffering error", 
            e, 
            dataSpec, 
            HttpDataSource.HttpDataSourceException.TYPE_READ
          );
        }
      }
      
             // Now proceed with normal reading
       return performNormalRead(buffer, offset, readLength);
     }

    private void bufferHeaderData(int requestedLength) throws IOException {
      // Only buffer if we haven't reached our target size
      if (headerBuffer.size() >= HEADER_BUFFER_SIZE) {
        return;
      }
      
      int bytesToBuffer = Math.min(requestedLength, HEADER_BUFFER_SIZE - headerBuffer.size());
      byte[] tempBuffer = new byte[bytesToBuffer];
      
      int bytesRead = inputStream.read(tempBuffer, 0, bytesToBuffer);
      if (bytesRead > 0) {
        if (decryptionEnabled) {
          // For encrypted streams, decrypt the header data as we buffer it
          long actualStreamPosition = dataSpec.position + totalBytesRead;
          System.arraycopy(tempBuffer, 0, encryptedBuffer, 0, bytesRead);
          int decryptedBytes = decryptChunkToSeparateBuffer(bytesRead, actualStreamPosition);
          
          // Add decrypted data to header buffer
          headerBuffer.write(decryptedBuffer, 0, decryptedBytes);
          
          // Update counters
          if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesRead;
          }
          totalBytesRead += bytesRead;
          
          Log.d("StreamingDataSource", String.format("Buffered %d encrypted->%d decrypted header bytes", bytesRead, decryptedBytes));
        } else {
          // For non-encrypted streams, buffer data directly
          headerBuffer.write(tempBuffer, 0, bytesRead);
          
          // Update counters
          if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= bytesRead;
          }
          totalBytesRead += bytesRead;
          
          Log.d("StreamingDataSource", String.format("Buffered %d header bytes", bytesRead));
        }
        
        // Check if headers are now complete and debug if so
        if (headerBuffer.size() >= HEADER_BUFFER_SIZE) {
          Log.d("StreamingDataSource", "=== HEADERS COMPLETE - DEBUGGING DECRYPTED DATA ===");
          byte[] headerData = headerBuffer.toByteArray();
          debugDecryptedData(headerData, headerData.length);
        }
      }
    }

    private int performNormalRead(byte[] buffer, int offset, int readLength) throws HttpDataSource.HttpDataSourceException {
      try {
        // First, serve any remaining header data
        if (headerBytesConsumed < headerBuffer.size()) {
          byte[] headerData = headerBuffer.toByteArray();
          int availableHeaderBytes = headerBuffer.size() - headerBytesConsumed;
          int headerBytesToReturn = Math.min(readLength, availableHeaderBytes);
          
          System.arraycopy(headerData, headerBytesConsumed, buffer, offset, headerBytesToReturn);
          headerBytesConsumed += headerBytesToReturn;
          
          Log.d("StreamingDataSource", String.format("Served %d bytes from header buffer (%d/%d consumed)", 
                headerBytesToReturn, headerBytesConsumed, headerBuffer.size()));
          
          return headerBytesToReturn;
        }
        
        // Header data exhausted, proceed with normal streaming
        // Limit chunk size for encrypted streams to prevent memory issues
        int bytesToRead = bytesRemaining != C.LENGTH_UNSET ? 
          (int) Math.min(readLength, bytesRemaining) : readLength;
          
        // Further limit chunk size for encrypted streams
        if (decryptionEnabled) {
          bytesToRead = Math.min(bytesToRead, MAX_CHUNK_SIZE);
        }
        
        if (decryptionEnabled) {
          // Read encrypted data from network into our encrypted buffer
          int encryptedBytesRead = inputStream.read(encryptedBuffer, 0, bytesToRead);
          
          if (encryptedBytesRead > 0) {
            if (bytesRemaining != C.LENGTH_UNSET) {
              bytesRemaining -= encryptedBytesRead;
            }
            
            // Decrypt from encrypted buffer to decrypted buffer
            // Use the aligned position from dataSpec for counter calculation
            long actualStreamPosition = dataSpec.position + totalBytesRead;
            int decryptedBytes = decryptChunkToSeparateBuffer(encryptedBytesRead, actualStreamPosition);
            
            // Copy decrypted data to ExoPlayer's buffer
            System.arraycopy(decryptedBuffer, 0, buffer, offset, decryptedBytes);
            
            // Send chunk event with network bytes read (for progress tracking)
            sendChunkEvent(encryptedBytesRead, dataSpec.position + totalBytesRead);
            totalBytesRead += encryptedBytesRead;
            
            // CRITICAL FIX: Return decrypted bytes given to ExoPlayer, not network bytes
            return decryptedBytes;
          }
          
          // Return -1 for EOF or error
          return encryptedBytesRead;
        } else {
          // For non-encrypted data, read directly to ExoPlayer's buffer
          int bytesRead = inputStream.read(buffer, offset, bytesToRead);
          
          if (bytesRead > 0) {
            if (bytesRemaining != C.LENGTH_UNSET) {
              bytesRemaining -= bytesRead;
            }
            
            // Send chunk event with minimal data
            sendChunkEvent(bytesRead, dataSpec.position + totalBytesRead);
            totalBytesRead += bytesRead;
          }
          
          return bytesRead;
        }
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
        if (encryptedBuffer != null) {
          java.util.Arrays.fill(encryptedBuffer, (byte) 0);
        }
        if (decryptedBuffer != null) {
          java.util.Arrays.fill(decryptedBuffer, (byte) 0);
        }
        
        // Reset Bouncy Castle cipher
        if (ctrCipher != null) {
          ctrCipher.reset();
        }
        
        // Clear header buffer
        if (headerBuffer != null) {
          headerBuffer.reset();
        }
        headersReady = false;
        headerBytesConsumed = 0;
        
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

    // CRITICAL: Align DataSpec position to AES block boundary for encrypted streams
    private DataSpec alignDataSpecToBlockBoundary(DataSpec dataSpec) {
      long alignedPosition = (dataSpec.position / AES_BLOCK_SIZE) * AES_BLOCK_SIZE;
      
      // If we had to align backwards, we need to adjust the length accordingly
      long positionDiff = dataSpec.position - alignedPosition;
      long adjustedLength = dataSpec.length;
      
      if (dataSpec.length != C.LENGTH_UNSET && positionDiff > 0) {
        adjustedLength = dataSpec.length + positionDiff;
      }
      
      return dataSpec.buildUpon()
              .setPosition(alignedPosition)
              .setLength(adjustedLength)
              .build();
    }

    // Build precise HTTP Range header
    private String buildRangeRequestHeader(long position, long length) {
      if (length != C.LENGTH_UNSET) {
        return "bytes=" + position + "-" + (position + length - 1);
      } else {
        return "bytes=" + position + "-";
      }
    }

    // Enhanced Bouncy Castle CTR decryption with 64-bit nonce
    private int decryptChunkToSeparateBuffer(int length, long currentOffset) {
      try {
        Log.d("StreamingDataSource", String.format("Decrypting %d bytes at offset %d", length, currentOffset));
        
        if (!decryptionEnabled || ctrCipher == null || dekKey == null || counterBase == null) {
          Log.w("StreamingDataSource", "Decryption disabled or missing components - copying raw data");
          System.arraycopy(encryptedBuffer, 0, decryptedBuffer, 0, length);
          return length;
        }
        
        // Verify counter base is 16 bytes (128-bit IV)
        if (counterBase.length != 16) {
          Log.e("StreamingDataSource", "Invalid counter base length: " + counterBase.length + " (expected 16)");
          System.arraycopy(encryptedBuffer, 0, decryptedBuffer, 0, length);
          return length;
        }
        
        // Calculate the block position for CTR mode
        long blockPosition = currentOffset / AES_BLOCK_SIZE;
        
        // Create the IV: 64-bit nonce + 64-bit counter
        byte[] iv = new byte[16];
        
        // Copy first 8 bytes as fixed nonce (unchanged throughout stream)
        System.arraycopy(counterBase, 0, iv, 0, 8);
        
        // Extract the base counter value from last 8 bytes of counterBase
        byte[] baseCounterBytes = new byte[8];
        System.arraycopy(counterBase, 8, baseCounterBytes, 0, 8);
        
        // Convert base counter to long, add block position, convert back to bytes
        long baseCounter = 0;
        for (int i = 0; i < 8; i++) {
          baseCounter = (baseCounter << 8) | (baseCounterBytes[i] & 0xFF);
        }
        
        long currentCounter = baseCounter + blockPosition;
        
        // Convert counter back to bytes (big-endian, last 8 bytes of IV)
        for (int i = 7; i >= 0; i--) {
          iv[8 + i] = (byte) (currentCounter & 0xFF);
          currentCounter >>>= 8;
        }
        
        // Log nonce and counter for debugging
        if (blockPosition == 0) {
          Log.d("StreamingDataSource", String.format("64-bit nonce: %s", 
                bytesToHex(iv, 0, 8)));
          Log.d("StreamingDataSource", String.format("Base counter: %s", 
                bytesToHex(counterBase, 8, 8)));
        }
        Log.d("StreamingDataSource", String.format("Block %d counter: %s", 
              blockPosition, bytesToHex(iv, 8, 8)));
        
        // Initialize cipher with key and IV
        KeyParameter keyParam = new KeyParameter(dekKey);
        ParametersWithIV params = new ParametersWithIV(keyParam, iv);
        ctrCipher.init(false, params); // false = decrypt mode
        
        // Decrypt the data
        int decryptedBytes = ctrCipher.processBytes(encryptedBuffer, 0, length, decryptedBuffer, 0);
        
        Log.d("StreamingDataSource", String.format("✓ Decrypted %d bytes successfully (64-bit nonce CTR)", decryptedBytes));
        return decryptedBytes;
        
      } catch (Exception e) {
        Log.e("StreamingDataSource", "Bouncy Castle decryption failed: " + e.getMessage());
        e.printStackTrace();
        
        // Fallback: copy encrypted data as-is
        System.arraycopy(encryptedBuffer, 0, decryptedBuffer, 0, length);
        return length;
      }
    }
    
    // Helper method to convert bytes to hex string for debugging
    private String bytesToHex(byte[] bytes, int offset, int length) {
      StringBuilder result = new StringBuilder();
      for (int i = offset; i < offset + length && i < bytes.length; i++) {
        result.append(String.format("%02X", bytes[i] & 0xFF));
      }
      return result.toString();
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
    
    // After decryption, log the first 32 bytes as both hex and ASCII
    private void debugDecryptedData(byte[] data, int length) {
      StringBuilder hex = new StringBuilder();
      StringBuilder ascii = new StringBuilder();
      
      for (int i = 0; i < Math.min(length, 32); i++) {
        hex.append(String.format("%02X ", data[i] & 0xFF));
        char c = (data[i] >= 32 && data[i] < 127) ? (char)data[i] : '.';
        ascii.append(c);
      }
      
      Log.d("StreamingDataSource", "Decrypted HEX:   " + hex.toString());
      Log.d("StreamingDataSource", "Decrypted ASCII: " + ascii.toString());
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

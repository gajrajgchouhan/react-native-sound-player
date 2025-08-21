//
//  RNSoundPlayer
//
//  Created by Johnson Su on 2018-07-10.
//

#import "RNSoundPlayer.h"
#import <AVFoundation/AVFoundation.h>
#import <CommonCrypto/CommonCryptor.h>

@implementation RNSoundPlayer
{
    bool hasListeners;
}

static NSString *const EVENT_SETUP_ERROR = @"OnSetupError";
static NSString *const EVENT_FINISHED_LOADING = @"FinishedLoading";
static NSString *const EVENT_FINISHED_LOADING_FILE = @"FinishedLoadingFile";
static NSString *const EVENT_FINISHED_LOADING_URL = @"FinishedLoadingURL";
static NSString *const EVENT_FINISHED_PLAYING = @"FinishedPlaying";
static NSString *const EVENT_CHUNK_RECEIVED = @"OnChunkReceived";

RCT_EXPORT_MODULE();

@synthesize bridge = _bridge;

+ (BOOL)requiresMainQueueSetup {
    return YES;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        self.loopCount = 0;
        
        // Initialize encryption properties
        self.encryptionEnabled = NO;
        self.useCustomDurationAndBitrate = NO;
        self.encryptedBitrate = 0;
        self.encryptedDuration = 0.0f;
        self.totalBytesProcessed = 0;
        
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(itemDidFinishPlaying:)
                                                     name:AVPlayerItemDidPlayToEndTimeNotification
                                                   object:nil];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    if (self.streamingSession) {
        [self.streamingSession invalidateAndCancel];
        self.streamingSession = nil;
    }
    
    // Clean up encryption properties
    self.dekKey = nil;
    self.counterBase = nil;
    self.encryptionEnabled = NO;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[EVENT_FINISHED_PLAYING, EVENT_FINISHED_LOADING, EVENT_FINISHED_LOADING_URL, EVENT_FINISHED_LOADING_FILE, EVENT_SETUP_ERROR, EVENT_CHUNK_RECEIVED];
}

-(void)startObserving {
    hasListeners = YES;
}

-(void)stopObserving {
    hasListeners = NO;
}

RCT_EXPORT_METHOD(playUrl:(NSString *)url) {
    [self prepareUrl:url];
    if (self.avPlayer) {
        [self.avPlayer play];
    }
}

RCT_EXPORT_METHOD(loadUrl:(NSString *)url) {
    [self prepareUrl:url];
}

RCT_EXPORT_METHOD(playUrlWithStreaming:(NSString *)url) {
    [self prepareUrlWithStreaming:url];
    if (self.avPlayer) {
        [self.avPlayer play];
    }
}

RCT_EXPORT_METHOD(loadUrlWithStreaming:(NSString *)url) {
    [self prepareUrlWithStreaming:url];
}

RCT_EXPORT_METHOD(playUrlWithStreamingEncrypted:(NSString *)url dekHex:(NSString *)dekHex counterBaseHex:(NSString *)counterBaseHex bitrate:(NSInteger)bitrate duration:(float)duration) {
    [self prepareUrlWithStreamingEncrypted:url dekHex:dekHex counterBaseHex:counterBaseHex bitrate:bitrate duration:duration];
    if (self.avPlayer) {
        [self.avPlayer play];
    }
}

RCT_EXPORT_METHOD(loadUrlWithStreamingEncrypted:(NSString *)url dekHex:(NSString *)dekHex counterBaseHex:(NSString *)counterBaseHex bitrate:(NSInteger)bitrate duration:(float)duration) {
    [self prepareUrlWithStreamingEncrypted:url dekHex:dekHex counterBaseHex:counterBaseHex bitrate:bitrate duration:duration];
}

RCT_EXPORT_METHOD(playSoundFile:(NSString *)name ofType:(NSString *)type) {
    [self mountSoundFile:name ofType:type];
    if (self.player) {
        [self.player play];
    }
}

RCT_EXPORT_METHOD(playSoundFileWithDelay:(NSString *)name ofType:(NSString *)type delay:(double)delay) {
    [self mountSoundFile:name ofType:type];
    if (self.player) {
        [self.player playAtTime:(self.player.deviceCurrentTime + delay)];
    }
}

RCT_EXPORT_METHOD(loadSoundFile:(NSString *)name ofType:(NSString *)type) {
    [self mountSoundFile:name ofType:type];
}

RCT_EXPORT_METHOD(pause) {
    if (self.player != nil) {
        [self.player pause];
    }
    if (self.avPlayer != nil) {
        [self.avPlayer pause];
    }
}

RCT_EXPORT_METHOD(resume) {
    if (self.player != nil) {
        [self.player play];
    }
    if (self.avPlayer != nil) {
        [self.avPlayer play];
    }
}

RCT_EXPORT_METHOD(stop) {
    if (self.player != nil) {
        [self.player stop];
    }
    if (self.avPlayer != nil) {
        [self.avPlayer pause];
        [self.avPlayer seekToTime:kCMTimeZero];
    }
}

RCT_EXPORT_METHOD(seek:(float)seconds) {
    if (self.player != nil) {
        self.player.currentTime = seconds;
    }
    if (self.avPlayer != nil) {
        [self.avPlayer seekToTime:CMTimeMakeWithSeconds(seconds, NSEC_PER_SEC)];
    }
}

#if !TARGET_OS_TV
RCT_EXPORT_METHOD(setSpeaker:(BOOL)on) {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *error = nil;
    if (on) {
        [session setCategory:AVAudioSessionCategoryPlayAndRecord error:&error];
        [session overrideOutputAudioPort:AVAudioSessionPortOverrideSpeaker error:&error];
    } else {
        [session setCategory:AVAudioSessionCategoryPlayback error:&error];
        [session overrideOutputAudioPort:AVAudioSessionPortOverrideNone error:&error];
    }
    [session setActive:YES error:&error];
    if (error) {
        [self sendErrorEvent:error];
    }
}
#endif

RCT_EXPORT_METHOD(setMixAudio:(BOOL)on) {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *error = nil;
    if (on) {
        [session setCategory:AVAudioSessionCategoryPlayback withOptions:AVAudioSessionCategoryOptionMixWithOthers error:&error];
    } else {
        [session setCategory:AVAudioSessionCategoryPlayback withOptions:0 error:&error];
    }
    [session setActive:YES error:&error];
    if (error) {
        [self sendErrorEvent:error];
    }
}

RCT_EXPORT_METHOD(setVolume:(float)volume) {
    if (self.player != nil) {
        [self.player setVolume:volume];
    }
    if (self.avPlayer != nil) {
        [self.avPlayer setVolume:volume];
    }
}

RCT_EXPORT_METHOD(setNumberOfLoops:(NSInteger)loopCount) {
    self.loopCount = loopCount;
    if (self.player != nil) {
        [self.player setNumberOfLoops:loopCount];
    }
}

RCT_REMAP_METHOD(getInfo,
                 getInfoWithResolver:(RCTPromiseResolveBlock)resolve
                 rejecter:(RCTPromiseRejectBlock)reject) {
    if (self.player != nil) {
        NSDictionary *data = @{
            @"currentTime": [NSNumber numberWithDouble:[self.player currentTime]],
            @"duration": [NSNumber numberWithDouble:[self.player duration]]
        };
        resolve(data);
    } else if (self.avPlayer != nil) {
        CMTime currentTime = [[self.avPlayer currentItem] currentTime];
        CMTime duration = [[[self.avPlayer currentItem] asset] duration];
        
        NSMutableDictionary *data = [[NSMutableDictionary alloc] init];
        [data setObject:[NSNumber numberWithFloat:CMTimeGetSeconds(currentTime)] forKey:@"currentTime"];
        
        // Use custom duration for encrypted streams if available
        if (self.useCustomDurationAndBitrate && self.encryptedDuration > 0) {
            [data setObject:[NSNumber numberWithFloat:self.encryptedDuration] forKey:@"duration"];
            [data setObject:[NSNumber numberWithInteger:self.encryptedBitrate] forKey:@"bitrate"];
            [data setObject:[NSNumber numberWithBool:YES] forKey:@"customDuration"];
        } else {
            [data setObject:[NSNumber numberWithFloat:CMTimeGetSeconds(duration)] forKey:@"duration"];
        }
        
        resolve([data copy]);
    } else {
        resolve(nil);
    }
}

- (void)audioPlayerDidFinishPlaying:(AVAudioPlayer *)player successfully:(BOOL)flag {
    if (hasListeners) {
        [self sendEventWithName:EVENT_FINISHED_PLAYING body:@{@"success": [NSNumber numberWithBool:flag]}];
    }
}

- (void)itemDidFinishPlaying:(NSNotification *)notification {
    if (hasListeners) {
        [self sendEventWithName:EVENT_FINISHED_PLAYING body:@{@"success": [NSNumber numberWithBool:YES]}];
    }
}

- (void)mountSoundFile:(NSString *)name ofType:(NSString *)type {
    if (self.avPlayer) {
        self.avPlayer = nil;
    }

    NSString *soundFilePath = [[NSBundle mainBundle] pathForResource:name ofType:type];

    if (soundFilePath == nil) {
        NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
        NSString *documentsDirectory = [paths objectAtIndex:0];
        soundFilePath = [NSString stringWithFormat:@"%@.%@", [documentsDirectory stringByAppendingPathComponent:name], type];
    }

    NSURL *soundFileURL = [NSURL fileURLWithPath:soundFilePath];
    NSError *error = nil;
    self.player = [[AVAudioPlayer alloc] initWithContentsOfURL:soundFileURL error:&error];
    if (error) {
        [self sendErrorEvent:error];
        return;
    }
    [self.player setDelegate:self];
    [self.player setNumberOfLoops:self.loopCount];
    [self.player prepareToPlay];
    [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayback error:&error];
    if (error) {
        [self sendErrorEvent:error];
        return;
    }
    if (hasListeners) {
        [self sendEventWithName:EVENT_FINISHED_LOADING body:@{@"success": [NSNumber numberWithBool:YES]}];
        [self sendEventWithName:EVENT_FINISHED_LOADING_FILE body:@{@"success": [NSNumber numberWithBool:YES], @"name": name, @"type": type}];
    }
}

- (void)prepareUrl:(NSString *)url {
    if (self.player) {
        self.player = nil;
    }
    NSURL *soundURL = [NSURL URLWithString:url];
    self.avPlayer = [[AVPlayer alloc] initWithURL:soundURL];
    [self.avPlayer.currentItem addObserver:self forKeyPath:@"status" options:0 context:nil];
}

- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary<NSKeyValueChangeKey,id> *)change context:(void *)context {
    if (object == self.avPlayer.currentItem && [keyPath isEqualToString:@"status"] && hasListeners) {
        if (self.avPlayer.currentItem.status == AVPlayerItemStatusReadyToPlay) {
            NSMutableDictionary *loadingData = [[NSMutableDictionary alloc] init];
            [loadingData setObject:[NSNumber numberWithBool:YES] forKey:@"success"];
            
            if (self.encryptionEnabled) {
                [loadingData setObject:[NSNumber numberWithBool:YES] forKey:@"encrypted"];
                [loadingData setObject:[NSNumber numberWithInteger:self.encryptedBitrate] forKey:@"bitrate"];
                [loadingData setObject:[NSNumber numberWithFloat:self.encryptedDuration] forKey:@"duration"];
            }
            
            [self sendEventWithName:EVENT_FINISHED_LOADING body:[loadingData copy]];
            
            NSURL *url = [(AVURLAsset *)self.avPlayer.currentItem.asset URL];
            NSMutableDictionary *urlData = [[NSMutableDictionary alloc] init];
            [urlData setObject:[NSNumber numberWithBool:YES] forKey:@"success"];
            [urlData setObject:[url absoluteString] forKey:@"url"];
            
            if (self.encryptionEnabled) {
                [urlData setObject:[NSNumber numberWithBool:YES] forKey:@"encrypted"];
                [urlData setObject:[NSNumber numberWithInteger:self.encryptedBitrate] forKey:@"bitrate"];
                [urlData setObject:[NSNumber numberWithFloat:self.encryptedDuration] forKey:@"duration"];
            }
            
            [self sendEventWithName:EVENT_FINISHED_LOADING_URL body:[urlData copy]];
        } else if (self.avPlayer.currentItem.status == AVPlayerItemStatusFailed) {
            [self sendErrorEvent:self.avPlayer.currentItem.error];
        }
    }
}

- (void)sendErrorEvent:(NSError *)error {
	if (hasListeners) {
	    [self sendEventWithName:EVENT_SETUP_ERROR body:@{@"error": [error localizedDescription]}];
	}
}

- (void)prepareUrlWithStreaming:(NSString *)url {
    if (self.player) {
        self.player = nil;
    }
    
    // Create a custom URL scheme for streaming
    NSString *streamingUrl = [url stringByReplacingOccurrencesOfString:@"https://" withString:@"streaming://"];
    streamingUrl = [streamingUrl stringByReplacingOccurrencesOfString:@"http://" withString:@"streaming://"];
    NSURL *streamingURL = [NSURL URLWithString:streamingUrl];
    
    // Create AVURLAsset with custom scheme
    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:streamingURL options:nil];
    
    // Set up resource loader delegate for custom streaming
    [asset.resourceLoader setDelegate:self queue:dispatch_get_main_queue()];
    
    // Create player item and player
    AVPlayerItem *playerItem = [AVPlayerItem playerItemWithAsset:asset];
    self.avPlayer = [[AVPlayer alloc] initWithPlayerItem:playerItem];
    
    // Store original URL for actual loading
    [self.avPlayer.currentItem addObserver:self forKeyPath:@"status" options:0 context:nil];
    
    // Initialize streaming session
    NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
    self.streamingSession = [NSURLSession sessionWithConfiguration:config];
    self.streamingData = [[NSMutableData alloc] init];
}

- (void)prepareUrlWithStreamingEncrypted:(NSString *)url dekHex:(NSString *)dekHex counterBaseHex:(NSString *)counterBaseHex bitrate:(NSInteger)bitrate duration:(float)duration {
    if (self.player) {
        self.player = nil;
    }
    
    // Initialize encryption parameters
    self.dekKey = [self hexStringToNSData:dekHex];
    self.counterBase = [self hexStringToNSData:counterBaseHex];
    self.encryptionEnabled = (self.dekKey && self.counterBase);
    self.encryptedBitrate = bitrate;
    self.encryptedDuration = duration;
    self.useCustomDurationAndBitrate = YES;
    self.totalBytesProcessed = 0;
    
    if (!self.encryptionEnabled) {
        NSError *error = [NSError errorWithDomain:@"RNSoundPlayerError" code:1001 userInfo:@{NSLocalizedDescriptionKey: @"Invalid encryption parameters"}];
        [self sendErrorEvent:error];
        return;
    }
    
    // Create a custom URL scheme for encrypted streaming
    NSString *streamingUrl = [url stringByReplacingOccurrencesOfString:@"https://" withString:@"streaming://"];
    streamingUrl = [streamingUrl stringByReplacingOccurrencesOfString:@"http://" withString:@"streaming://"];
    NSURL *streamingURL = [NSURL URLWithString:streamingUrl];
    
    // Create AVURLAsset with custom scheme
    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:streamingURL options:nil];
    
    // Set up resource loader delegate for custom streaming
    [asset.resourceLoader setDelegate:self queue:dispatch_get_main_queue()];
    
    // Create player item and player
    AVPlayerItem *playerItem = [AVPlayerItem playerItemWithAsset:asset];
    self.avPlayer = [[AVPlayer alloc] initWithPlayerItem:playerItem];
    
    // Store original URL for actual loading
    [self.avPlayer.currentItem addObserver:self forKeyPath:@"status" options:0 context:nil];
    
    // Initialize streaming session
    NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
    self.streamingSession = [NSURLSession sessionWithConfiguration:config];
    self.streamingData = [[NSMutableData alloc] init];
}

- (BOOL)resourceLoader:(AVAssetResourceLoader *)resourceLoader shouldWaitForLoadingOfRequestedResource:(AVAssetResourceLoadingRequest *)loadingRequest {
    // Get the original URL by converting back from custom scheme
    NSString *originalUrl = [loadingRequest.request.URL.absoluteString stringByReplacingOccurrencesOfString:@"streaming://" withString:@"https://"];
    if ([originalUrl containsString:@"streaming://"]) {
        originalUrl = [loadingRequest.request.URL.absoluteString stringByReplacingOccurrencesOfString:@"streaming://" withString:@"http://"];
    }
    NSURL *url = [NSURL URLWithString:originalUrl];
    
    // Create request for the original URL
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    
    // Handle range requests for seeking
    if (loadingRequest.dataRequest.requestedOffset > 0) {
        NSString *rangeHeader = [NSString stringWithFormat:@"bytes=%lld-", loadingRequest.dataRequest.requestedOffset];
        [request setValue:rangeHeader forHTTPHeaderField:@"Range"];
    }
    
    // Create data task with chunk processing
    NSURLSessionDataTask *task = [self.streamingSession dataTaskWithRequest:request
        completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
            if (error) {
                [loadingRequest finishLoadingWithError:error];
                return;
            }
            
            // Process the complete response
            [self processStreamingResponse:response data:data loadingRequest:loadingRequest];
        }];
    
    [task resume];
    return YES;
}

- (void)processStreamingResponse:(NSURLResponse *)response data:(NSData *)data loadingRequest:(AVAssetResourceLoadingRequest *)loadingRequest {
    // Fill content information
    if (loadingRequest.contentInformationRequest) {
        loadingRequest.contentInformationRequest.contentLength = response.expectedContentLength;
        loadingRequest.contentInformationRequest.contentType = response.MIMEType;
        loadingRequest.contentInformationRequest.byteRangeAccessSupported = YES;
    }
    
    // Process data in chunks
    NSUInteger chunkSize = 8192; // 8KB chunks
    NSUInteger offset = 0;
    
    while (offset < data.length) {
        NSUInteger remainingBytes = data.length - offset;
        NSUInteger currentChunkSize = MIN(chunkSize, remainingBytes);
        
        NSData *chunk = [data subdataWithRange:NSMakeRange(offset, currentChunkSize)];
        
        // Process the chunk (your custom logic here)
        [self processChunk:chunk atPosition:loadingRequest.dataRequest.requestedOffset + offset];
        
        // Respond to the loading request with this chunk
        [loadingRequest.dataRequest respondWithData:chunk];
        
        offset += currentChunkSize;
    }
    
    [loadingRequest finishLoading];
}

- (void)processChunk:(NSData *)chunk atPosition:(long long)position {
    // Process decryption if encryption is enabled
    NSData *processedChunk = chunk;
    if (self.encryptionEnabled && self.dekKey && self.counterBase) {
        processedChunk = [self decryptChunk:chunk atPosition:position];
        if (!processedChunk) {
            processedChunk = chunk; // Fallback to original chunk if decryption fails
        }
    }
    
    // Update total bytes processed
    self.totalBytesProcessed += processedChunk.length;
    
    // Send chunk data to React Native
    if (hasListeners) {
        NSString *base64Data = [processedChunk base64EncodedStringWithOptions:0];
        NSDictionary *chunkData = @{
            @"chunkSize": @(processedChunk.length),
            @"position": @(position),
            @"data": base64Data,
            @"encrypted": @(self.encryptionEnabled),
            @"totalBytesProcessed": @(self.totalBytesProcessed)
        };
        [self sendEventWithName:EVENT_CHUNK_RECEIVED body:chunkData];
    }
}

// Helper method to convert hex string to NSData
- (NSData *)hexStringToNSData:(NSString *)hexString {
    if (!hexString || hexString.length == 0) {
        return nil;
    }
    
    // Remove any spaces or non-hex characters
    NSString *cleanHexString = [[hexString componentsSeparatedByCharactersInSet:[[NSCharacterSet characterSetWithCharactersInString:@"0123456789ABCDEFabcdef"] invertedSet]] componentsJoinedByString:@""];
    
    // Ensure even length
    if (cleanHexString.length % 2 != 0) {
        return nil;
    }
    
    NSMutableData *data = [[NSMutableData alloc] init];
    for (NSUInteger i = 0; i < cleanHexString.length; i += 2) {
        NSString *hexByte = [cleanHexString substringWithRange:NSMakeRange(i, 2)];
        unsigned int byte = 0;
        [[NSScanner scannerWithString:hexByte] scanHexInt:&byte];
        uint8_t byteValue = byte;
        [data appendBytes:&byteValue length:1];
    }
    
    return [data copy];
}

// AES CTR decryption method
- (NSData *)decryptChunk:(NSData *)encryptedData atPosition:(long long)position {
    if (!self.encryptionEnabled || !self.dekKey || !self.counterBase || !encryptedData) {
        return nil;
    }
    
    // Verify key and counter base lengths
    if (self.dekKey.length != kCCKeySizeAES256 && self.dekKey.length != kCCKeySizeAES192 && self.dekKey.length != kCCKeySizeAES128) {
        NSLog(@"[RNSoundPlayer] Invalid key size: %lu", (unsigned long)self.dekKey.length);
        return nil;
    }
    
    if (self.counterBase.length != kCCBlockSizeAES128) {
        NSLog(@"[RNSoundPlayer] Invalid counter base size: %lu", (unsigned long)self.counterBase.length);
        return nil;
    }
    
    // Calculate block position for CTR mode
    long long blockPosition = position / kCCBlockSizeAES128;
    
    // Create IV: 64-bit nonce + 64-bit counter (following Android implementation)
    uint8_t iv[kCCBlockSizeAES128];
    const uint8_t *counterBaseBytes = (const uint8_t *)[self.counterBase bytes];
    
    // Copy first 8 bytes as fixed nonce
    memcpy(iv, counterBaseBytes, 8);
    
    // Extract base counter from last 8 bytes of counterBase
    uint64_t baseCounter = 0;
    for (int i = 0; i < 8; i++) {
        baseCounter = (baseCounter << 8) | counterBaseBytes[8 + i];
    }
    
    // Calculate current counter
    uint64_t currentCounter = baseCounter + blockPosition;
    
    // Convert counter back to bytes (big-endian, last 8 bytes of IV)
    for (int i = 7; i >= 0; i--) {
        iv[8 + i] = (uint8_t)(currentCounter & 0xFF);
        currentCounter >>= 8;
    }
    
    // Perform AES CTR decryption
    NSMutableData *decryptedData = [NSMutableData dataWithLength:encryptedData.length];
    size_t decryptedLength = 0;
    
    CCCryptorStatus status = CCCrypt(
        kCCDecrypt,
        kCCAlgorithmAES,
        kCCOptionECBMode, // We'll handle CTR mode manually
        [self.dekKey bytes],
        self.dekKey.length,
        nil, // No IV for ECB mode, we'll XOR manually
        [encryptedData bytes],
        encryptedData.length,
        [decryptedData mutableBytes],
        decryptedData.length,
        &decryptedLength
    );
    
    if (status != kCCSuccess) {
        NSLog(@"[RNSoundPlayer] Decryption failed with status: %d", status);
        return nil;
    }
    
    // For proper CTR mode, we need to encrypt the counter and XOR with data
    // Since CommonCrypto doesn't have direct CTR support, we'll implement it manually
    NSMutableData *keystream = [NSMutableData dataWithLength:encryptedData.length];
    NSMutableData *currentIV = [NSMutableData dataWithBytes:iv length:kCCBlockSizeAES128];
    
    // Generate keystream by encrypting consecutive counter values
    for (NSUInteger offset = 0; offset < encryptedData.length; offset += kCCBlockSizeAES128) {
        uint8_t encryptedCounter[kCCBlockSizeAES128];
        size_t encryptedCounterLength = 0;
        
        CCCryptorStatus counterStatus = CCCrypt(
            kCCEncrypt,
            kCCAlgorithmAES,
            0, // No padding for block mode
            [self.dekKey bytes],
            self.dekKey.length,
            nil,
            [currentIV bytes],
            kCCBlockSizeAES128,
            encryptedCounter,
            kCCBlockSizeAES128,
            &encryptedCounterLength
        );
        
        if (counterStatus != kCCSuccess) {
            NSLog(@"[RNSoundPlayer] Counter encryption failed with status: %d", counterStatus);
            return nil;
        }
        
        // Copy encrypted counter to keystream
        NSUInteger bytesToCopy = MIN(kCCBlockSizeAES128, encryptedData.length - offset);
        [keystream replaceBytesInRange:NSMakeRange(offset, bytesToCopy) withBytes:encryptedCounter length:bytesToCopy];
        
        // Increment counter for next block
        uint8_t *ivBytes = (uint8_t *)[currentIV mutableBytes];
        // Increment the counter part (last 8 bytes) in big-endian
        for (int i = 15; i >= 8; i--) {
            if (++ivBytes[i] != 0) break;
        }
    }
    
    // XOR encrypted data with keystream to get decrypted data
    const uint8_t *encryptedBytes = (const uint8_t *)[encryptedData bytes];
    const uint8_t *keystreamBytes = (const uint8_t *)[keystream bytes];
    uint8_t *decryptedBytes = (uint8_t *)[decryptedData mutableBytes];
    
    for (NSUInteger i = 0; i < encryptedData.length; i++) {
        decryptedBytes[i] = encryptedBytes[i] ^ keystreamBytes[i];
    }
    
    [decryptedData setLength:encryptedData.length];
    
    NSLog(@"[RNSoundPlayer] Successfully decrypted %lu bytes at position %lld", (unsigned long)encryptedData.length, position);
    
    return [decryptedData copy];
}

@end

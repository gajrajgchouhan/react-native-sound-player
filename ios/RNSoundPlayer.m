//
//  RNSoundPlayer
//
//  Created by Johnson Su on 2018-07-10.
//

#import "RNSoundPlayer.h"
#import <AVFoundation/AVFoundation.h>

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
        NSDictionary *data = @{
            @"currentTime": [NSNumber numberWithFloat:CMTimeGetSeconds(currentTime)],
            @"duration": [NSNumber numberWithFloat:CMTimeGetSeconds(duration)]
        };
        resolve(data);
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
            [self sendEventWithName:EVENT_FINISHED_LOADING body:@{@"success": [NSNumber numberWithBool:YES]}];
            NSURL *url = [(AVURLAsset *)self.avPlayer.currentItem.asset URL];
            [self sendEventWithName:EVENT_FINISHED_LOADING_URL body:@{@"success": [NSNumber numberWithBool:YES], @"url": [url absoluteString]}];
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
    // Your custom chunk processing logic here
    // For example, you could:
    // - Decrypt the chunk
    // - Apply audio effects
    // - Log streaming progress
    // - Send chunk data to React Native
    
    if (hasListeners) {
        NSString *base64Data = [chunk base64EncodedStringWithOptions:0];
        NSDictionary *chunkData = @{
            @"chunkSize": @(chunk.length),
            @"position": @(position),
            @"data": base64Data
        };
        [self sendEventWithName:EVENT_CHUNK_RECEIVED body:chunkData];
    }
}

@end

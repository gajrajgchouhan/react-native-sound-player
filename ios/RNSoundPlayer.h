//
//  RNSoundPlayer
//
//  Created by Johnson Su on 2018-07-10.
//

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <AVFoundation/AVFoundation.h>

@interface RNSoundPlayer : RCTEventEmitter <RCTBridgeModule, AVAudioPlayerDelegate, AVAssetResourceLoaderDelegate>

@property (nonatomic, strong) AVAudioPlayer *player;
@property (nonatomic, strong) AVPlayer *avPlayer;
@property (nonatomic, strong) NSURLSession *streamingSession;
@property (nonatomic, strong) NSMutableData *streamingData;
@property (nonatomic, assign) NSInteger loopCount;

// Encryption properties
@property (nonatomic, strong) NSData *dekKey;
@property (nonatomic, strong) NSData *counterBase;
@property (nonatomic, assign) BOOL encryptionEnabled;
@property (nonatomic, assign) NSInteger encryptedBitrate;
@property (nonatomic, assign) float encryptedDuration;
@property (nonatomic, assign) BOOL useCustomDurationAndBitrate;
@property (nonatomic, assign) long long totalBytesProcessed;

@end

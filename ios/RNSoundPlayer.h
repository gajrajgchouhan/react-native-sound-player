//
//  RNSoundPlayer
//
//  Created by Johnson Su on 2018-07-10.
//

#import <React/RCTBridgeModule.h>
#import <AVFoundation/AVFoundation.h>
#import <React/RCTEventEmitter.h>

@interface RNSoundPlayer : RCTEventEmitter <RCTBridgeModule, AVAudioPlayerDelegate, AVAssetResourceLoaderDelegate>
@property (nonatomic, strong) AVAudioPlayer *player;
@property (nonatomic, strong) AVPlayer *avPlayer;
@property (nonatomic, strong) NSURLSession *streamingSession;
@property (nonatomic, strong) NSMutableData *streamingData;
@property (nonatomic) int loopCount;
@end

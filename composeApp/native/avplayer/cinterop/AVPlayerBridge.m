#import "AVPlayerBridge.h"
#import <AVFAudio/AVFAudio.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreImage/CoreImage.h>
#import <CoreVideo/CoreVideo.h>
#import <MediaToolbox/MediaToolbox.h>

@interface AVPlayerBridge ()
@property (nonatomic, copy) AudioTapCallback tapCallback;
@property (nonatomic, assign) BOOL audioTapInstalled;
@end

typedef struct {
    __unsafe_unretained AVPlayerBridge *bridge;
    Float64 sampleRate;
    int channels;
} TapContext;

static void tapInit(MTAudioProcessingTapRef tap, void *clientInfo, void **tapStorageOut) {
    TapContext *context = calloc(1, sizeof(TapContext));
    context->bridge = (__bridge AVPlayerBridge *)clientInfo;
    *tapStorageOut = context;
}

static void tapFinalize(MTAudioProcessingTapRef tap) {
    TapContext *context = (TapContext *)MTAudioProcessingTapGetStorage(tap);
    free(context);
}

static void tapPrepare(MTAudioProcessingTapRef tap, CMItemCount maxFrames, const AudioStreamBasicDescription *processingFormat) {
    TapContext *context = (TapContext *)MTAudioProcessingTapGetStorage(tap);
    context->sampleRate = processingFormat->mSampleRate;
    context->channels = processingFormat->mChannelsPerFrame;
}

static void tapUnprepare(MTAudioProcessingTapRef tap) {
}

static void tapProcess(MTAudioProcessingTapRef tap, CMItemCount numberFrames, MTAudioProcessingTapFlags flags, AudioBufferList *bufferListInOut, CMItemCount *numberFramesOut, MTAudioProcessingTapFlags *flagsOut) {
    TapContext *context = (TapContext *)MTAudioProcessingTapGetStorage(tap);
    
    OSStatus status = MTAudioProcessingTapGetSourceAudio(tap, numberFrames, bufferListInOut, flagsOut, NULL, numberFramesOut);
    if (status != noErr) return;
    
    if (context->bridge.tapCallback && bufferListInOut->mNumberBuffers > 0) {
        AudioBuffer *buffer = &bufferListInOut->mBuffers[0];
        const float *pcmData = (const float *)buffer->mData;
        if (pcmData) {
            context->bridge.tapCallback(pcmData, (int)*numberFramesOut, context->channels, (int)context->sampleRate);
        }
    }
}

@interface AVPlayerBridgeView : UIView
@property (nonatomic, strong) AVPlayer *player;
@end

@implementation AVPlayerBridgeView
+ (Class)layerClass {
    return [AVPlayerLayer class];
}
- (AVPlayer *)player {
    return [(AVPlayerLayer *)self.layer player];
}
- (void)setPlayer:(AVPlayer *)player {
    [(AVPlayerLayer *)self.layer setPlayer:player];
    [(AVPlayerLayer *)self.layer setVideoGravity:AVLayerVideoGravityResizeAspect];
}
@end

@implementation AVPlayerBridge

+ (BOOL)configurePlaybackSession {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *error = nil;
    [session setCategory:AVAudioSessionCategoryPlayback
                    mode:AVAudioSessionModeDefault
                 options:0
                   error:&error];
    if (error != nil) {
        return NO;
    }
    [session setActive:YES error:&error];
    return error == nil;
}

+ (void)capturePreviewFrameForURL:(NSURL *)url
                            atTime:(CMTime)time
                        completion:(PreviewFrameCallback)completion {
    dispatch_async(dispatch_get_main_queue(), ^{
        AVPlayerItem *item = [AVPlayerItem playerItemWithURL:url];
        NSDictionary *settings = @{
            (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
        };
        AVPlayerItemVideoOutput *output = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:settings];
        [item addOutput:output];

        AVPlayer *player = [AVPlayer playerWithPlayerItem:item];
        CIContext *context = [CIContext contextWithOptions:nil];
        __block BOOL didComplete = NO;
        __block NSUInteger attempts = 0;
        __block void (^copyFrame)(void);

        void (^finish)(UIImage *, NSError *) = ^(UIImage *image, NSError *error) {
            if (didComplete) return;
            didComplete = YES;
            copyFrame = nil;
            [player pause];
            completion(image, error);
        };

        copyFrame = ^{
            CMTime itemTime = item.currentTime;
            if ([output hasNewPixelBufferForItemTime:itemTime]) {
                CVPixelBufferRef pixelBuffer = [output copyPixelBufferForItemTime:itemTime itemTimeForDisplay:nil];
                if (pixelBuffer != NULL) {
                    CIImage *ciImage = [CIImage imageWithCVPixelBuffer:pixelBuffer];
                    CGImageRef cgImage = [context createCGImage:ciImage fromRect:ciImage.extent];
                    CVPixelBufferRelease(pixelBuffer);
                    if (cgImage != NULL) {
                        UIImage *image = [UIImage imageWithCGImage:cgImage];
                        CGImageRelease(cgImage);
                        finish(image, nil);
                        return;
                    }
                }
            }

            attempts += 1;
            if (attempts >= 40) {
                NSError *error = [NSError errorWithDomain:@"PreviewFrameEngine"
                                                     code:1
                                                 userInfo:@{NSLocalizedDescriptionKey: @"Timed out waiting for a decoded video frame."}];
                finish(nil, error);
                return;
            }
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.05 * NSEC_PER_SEC)), dispatch_get_main_queue(), copyFrame);
        };

        [player seekToTime:time
            toleranceBefore:kCMTimeZero
             toleranceAfter:kCMTimeZero
          completionHandler:^(BOOL finished) {
            if (!finished) {
                NSError *error = [NSError errorWithDomain:@"PreviewFrameEngine"
                                                     code:2
                                                 userInfo:@{NSLocalizedDescriptionKey: @"AVPlayer could not seek to the preview position."}];
                finish(nil, error);
                return;
            }
            [player play];
            copyFrame();
        }];

        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            if (!didComplete) {
                NSError *error = [NSError errorWithDomain:@"PreviewFrameEngine"
                                                     code:3
                                                 userInfo:@{NSLocalizedDescriptionKey: @"Timed out preparing the video preview."}];
                finish(nil, error);
            }
        });
    });
}

- (instancetype)initWithURL:(NSURL *)url {
    self = [super init];
    if (self) {
        _player = [[AVPlayer alloc] initWithURL:url];
        _playerLayer = [AVPlayerLayer playerLayerWithPlayer:_player];
        _playerLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    }
    return self;
}

- (void)play { [self.player play]; }
- (void)pause { [self.player pause]; }
- (float)rate { return [self.player rate]; }
- (void)setMuted:(BOOL)muted { [self.player setMuted:muted]; }

- (CMTime)duration {
    AVPlayerItem *item = self.player.currentItem;
    return item ? item.duration : kCMTimeInvalid;
}

- (void)seekToTime:(CMTime)time { [self.player seekToTime:time]; }

- (id)addPeriodicTimeObserverForInterval:(CMTime)interval
                                   queue:(dispatch_queue_t)queue
                              usingBlock:(void (^)(CMTime time))block {
    return [self.player addPeriodicTimeObserverForInterval:interval
                                                     queue:queue
                                                usingBlock:block];
}

- (void)removeTimeObserver:(id)observer {
    [self.player removeTimeObserver:observer];
}

- (void)replaceCurrentItemWithItem:(AVPlayerItem *)item {
    [self.player replaceCurrentItemWithPlayerItem:item];
}

- (UIView *)createPlayerView {
    AVPlayerBridgeView *view = [[AVPlayerBridgeView alloc] initWithFrame:CGRectZero];
    view.player = self.player;
    return view;
}

- (void)installAudioTapWithCallback:(AudioTapCallback)callback {
    self.tapCallback = callback;
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return;
    [self installAudioTapForItem:item remainingAttempts:300];
}

// AVPlayerItem.tracks is empty until AVPlayer has loaded the HLS item's tracks.
// Installing the tap during screen composition therefore used to return before
// any audio existed and was never retried, leaving the caption router silent.
- (void)installAudioTapForItem:(AVPlayerItem *)item remainingAttempts:(NSUInteger)remainingAttempts {
    if (self.audioTapInstalled || item != self.player.currentItem || !self.tapCallback) return;

    AVPlayerItemTrack *audioTrack = nil;
    for (AVPlayerItemTrack *track in item.tracks) {
        if ([track.assetTrack.mediaType isEqualToString:AVMediaTypeAudio]) {
            audioTrack = track;
            break;
        }
    }
    if (!audioTrack) {
        if (remainingAttempts > 0) {
            __weak AVPlayerBridge *weakSelf = self;
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.1 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                [weakSelf installAudioTapForItem:item remainingAttempts:remainingAttempts - 1];
            });
        } else {
            NSLog(@"AVPlayerBridge: audio track did not load; captions are unavailable for this item.");
        }
        return;
    }

    MTAudioProcessingTapCallbacks callbacks;
    callbacks.version = kMTAudioProcessingTapCallbacksVersion_0;
    callbacks.clientInfo = (__bridge void *)self;
    callbacks.init = tapInit;
    callbacks.prepare = tapPrepare;
    callbacks.process = tapProcess;
    callbacks.unprepare = tapUnprepare;
    callbacks.finalize = tapFinalize;

    MTAudioProcessingTapRef tap;
    OSStatus status = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap);
    if (status != noErr) {
        NSLog(@"AVPlayerBridge: could not create audio tap (%d).", (int)status);
        return;
    }

    AVMutableAudioMixInputParameters *params = [AVMutableAudioMixInputParameters audioMixInputParametersWithTrack:audioTrack.assetTrack];
    params.audioTapProcessor = tap;
    
    AVMutableAudioMix *audioMix = [AVMutableAudioMix audioMix];
    audioMix.inputParameters = @[params];
    item.audioMix = audioMix;
    self.audioTapInstalled = YES;

    CFRelease(tap);
}

// Quality / Rendition controls

- (double)preferredPeakBitRate {
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return 0.0;
    return item.preferredPeakBitRate;
}

- (void)setPreferredPeakBitRate:(double)preferredPeakBitRate {
    AVPlayerItem *item = self.player.currentItem;
    if (item) {
        item.preferredPeakBitRate = preferredPeakBitRate;
    }
}

- (void)setVideoEnabled:(BOOL)enabled {
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return;
    for (AVPlayerItemTrack *track in item.tracks) {
        if ([track.assetTrack.mediaType isEqualToString:AVMediaTypeVideo]) {
            track.enabled = enabled;
        }
    }
}

// Metrics

- (CGSize)videoSize {
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return CGSizeZero;
    return item.presentationSize;
}

- (CMTime)bufferedDuration {
    AVPlayerItem *item = self.player.currentItem;
    if (!item || item.loadedTimeRanges.count == 0) return kCMTimeZero;
    CMTimeRange timeRange = [item.loadedTimeRanges.firstObject CMTimeRangeValue];
    return CMTimeAdd(timeRange.start, timeRange.duration);
}

@end

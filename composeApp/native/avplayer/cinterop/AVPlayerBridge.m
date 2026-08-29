#import "AVPlayerBridge.h"
#import <AVFAudio/AVFAudio.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreImage/CoreImage.h>
#import <CoreVideo/CoreVideo.h>

// Length of an ID3v2 tag at the head of [data], or 0 if there is none. HLS
// packed-audio segments carry one (it holds the PTS as a PRIV frame); the ADTS
// parser wants the raw frames, so it has to come off first.
static NSUInteger ICSID3TagLength(NSData *data) {
    if (data.length < 10) return 0;
    const uint8_t *bytes = (const uint8_t *)data.bytes;
    if (memcmp(bytes, "ID3", 3) != 0) return 0;
    // Bytes 6..9 are a "syncsafe" integer: 7 significant bits each.
    NSUInteger size = ((NSUInteger)(bytes[6] & 0x7F) << 21)
                    | ((NSUInteger)(bytes[7] & 0x7F) << 14)
                    | ((NSUInteger)(bytes[8] & 0x7F) << 7)
                    | ((NSUInteger)(bytes[9] & 0x7F));
    NSUInteger total = 10 + size;
    return total <= data.length ? total : 0;
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

+ (BOOL)decodeAudioSegment:(NSData *)data callback:(AudioSegmentCallback)callback {
    if (data.length == 0 || callback == nil) return NO;

    NSUInteger offset = ICSID3TagLength(data);
    if (offset >= data.length) return NO;
    NSData *adts = offset > 0 ? [data subdataWithRange:NSMakeRange(offset, data.length - offset)]
                              : data;

    // AVAudioFile reads from a URL, not memory, so the frames go to a temp file.
    // The extension matters: it is how AudioFile picks the ADTS parser.
    NSString *name = [NSString stringWithFormat:@"ics-caption-%@.aac", [[NSUUID UUID] UUIDString]];
    NSURL *tmp = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:name]];
    if (![adts writeToURL:tmp atomically:YES]) return NO;

    BOOL ok = NO;
    @try {
        NSError *error = nil;
        AVAudioFile *file = [[AVAudioFile alloc] initForReading:tmp error:&error];
        if (file == nil || error != nil) return NO;

        AVAudioFormat *format = file.processingFormat;
        AVAudioFrameCount frames = (AVAudioFrameCount)file.length;
        if (frames == 0 || format.channelCount == 0) return NO;

        AVAudioPCMBuffer *buffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format
                                                                 frameCapacity:frames];
        if (buffer == nil || ![file readIntoBuffer:buffer error:&error] || error != nil) return NO;

        AVAudioFrameCount decoded = buffer.frameLength;
        if (decoded == 0 || buffer.floatChannelData == NULL) return NO;

        // processingFormat is always deinterleaved float32, so downmix here and
        // hand back a single channel — the Kotlin side then treats it exactly
        // like any other mono PCM source.
        AVAudioChannelCount channels = format.channelCount;
        float *mono = (float *)malloc(sizeof(float) * decoded);
        if (mono == NULL) return NO;
        for (AVAudioFrameCount i = 0; i < decoded; i++) {
            float sum = 0.0f;
            for (AVAudioChannelCount c = 0; c < channels; c++) {
                sum += buffer.floatChannelData[c][i];
            }
            mono[i] = sum / (float)channels;
        }

        callback(mono, (int)decoded, 1, (int)format.sampleRate);
        free(mono);
        ok = YES;
    } @finally {
        [[NSFileManager defaultManager] removeItemAtURL:tmp error:nil];
    }
    return ok;
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

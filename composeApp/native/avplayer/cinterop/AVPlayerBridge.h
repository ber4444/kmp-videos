#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

typedef void (^AudioSegmentCallback)(const float *pcmData, int numFrames, int numChannels, int sampleRate);
typedef void (^PreviewFrameCallback)(UIImage * _Nullable image, NSError * _Nullable error);

/**
 * Thin Objective-C bridge over AVPlayer and AVPlayerLayer.
 *
 * Rationale: against the Xcode 26.5 SDK, Kotlin/Native's cinterop fails to
 * merge AVPlayer's and AVPlayerLayer's Objective-C category / property methods
 * (play/pause/rate/seekToTime/addPeriodicTimeObserver..., setPlayer:, etc.)
 * into the generated Kotlin classes, so those calls are unresolvable from
 * Kotlin. This wrapper re-exposes the calls needed by the player composable as
 * instance methods on a plain NSObject subclass, whose methods cinterop merges
 * correctly. It is a toolchain workaround, not application logic — keep it
 * minimal.
 */
@interface AVPlayerBridge : NSObject

@property (nonatomic, strong, readonly) AVPlayer *player;
@property (nonatomic, strong, readonly) AVPlayerLayer *playerLayer;

// Configures the shared AVAudioSession for `.playback` (background audio).
// Returns NO on failure. Lives here for the same category-merge reason as the
// rest of this class: AVAudioSession's setCategory/setActive are declared in
// AVFAudio categories that cinterop fails to merge onto the generated class.
+ (BOOL)configurePlaybackSession;

// Decodes one frame from any stream AVPlayer can play, including ordinary HLS
// playlists (which AVAssetImageGenerator cannot thumbnail unless they expose an
// I-frame-only rendition). The callback always runs on the main queue.
+ (void)capturePreviewFrameForURL:(NSURL *)url
                            atTime:(CMTime)time
                        completion:(PreviewFrameCallback)completion;

// Decodes one HLS "packed audio" segment — an ID3 header followed by raw ADTS
// AAC frames, which is what the `_aac` rendition serves — into mono float PCM.
//
// This exists because live captions cannot read the player's audio directly:
// MTAudioProcessingTap is not supported for HTTP Live Streaming, so an audio tap
// on the playing item is created and installed successfully but its process
// callback is never invoked. The caption pipeline instead fetches the audio-only
// rendition's segments over plain HTTP and decodes them here.
//
// Returns NO if the segment could not be decoded. [callback] is invoked
// synchronously, once, before returning; the pointer it receives is only valid
// for the duration of the call.
+ (BOOL)decodeAudioSegment:(NSData *)data callback:(AudioSegmentCallback)callback;

- (instancetype)initWithURL:(NSURL *)url;

// Transport
- (void)play;
- (void)pause;
- (float)rate;
- (void)setMuted:(BOOL)muted;

// Time / seeking (CMTime values)
- (CMTime)duration;
- (void)seekToTime:(CMTime)time;

// Periodic time observation (pass nil for queue to use the main queue).
- (id)addPeriodicTimeObserverForInterval:(CMTime)interval
                                   queue:(dispatch_queue_t)queue
                              usingBlock:(void (^)(CMTime time))block;
- (void)removeTimeObserver:(id)observer;

// Item lifecycle
- (void)replaceCurrentItemWithItem:(AVPlayerItem *)item;

// Creates a UIView whose backing layer is an AVPlayerLayer linked to this player.
// It automatically resizes the player layer to match its bounds.
- (UIView *)createPlayerView;

// Quality / Rendition controls
@property (nonatomic, assign) double preferredPeakBitRate;
- (void)setVideoEnabled:(BOOL)enabled;

// Metrics
- (CGSize)videoSize;
- (CMTime)bufferedDuration;

@end

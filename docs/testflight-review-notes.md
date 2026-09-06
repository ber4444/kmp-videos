# TestFlight beta review

Copy-paste material for App Store Connect → TestFlight → *Test Information*.
External beta review rejects a build whose sign-in a reviewer cannot get past, so
the demo account below is not optional — it is the whole reason the review-account
exemption exists (see [server/README.md](../server/README.md#feed-policy)).

---

## Beta App Description

> Apollo Videos streams live and recorded concert video from the Apollo community's
> event server.
>
> Browse a feed of past and in-progress events, each showing whether it is live now
> and how long it runs. Tap one to watch. Playback adapts quality to your connection
> and to the size of the video on screen, so a phone held upright does not download a
> stream sized for a television. Live events can be paused and rewound through the
> server's DVR window and jumped back to the live edge.
>
> Live captions can be switched on during playback and are translated in-band into
> your device's language, so the same English audio reads in whatever language iOS is
> set to.
>
> Recorded events can be downloaded for offline viewing. Playback continues in the
> background and in Picture in Picture, and video can be filled, fitted or zoomed and
> rotated to fullscreen.
>
> Access is limited to members of the Apollo Discord community, so the app opens on a
> sign-in screen. Reviewers should use the demo account below.

*(Roughly 1,100 characters. App Store Connect allows 4,000.)*

---

## What to Test

> Sign in with the demo Discord account in the review notes, then:
>
> 1. **Feed** — the gallery lists the demo videos with their durations and, for a
>    live event, a LIVE badge.
> 2. **Playback** — tap a video. Check the controls auto-hide, scrubbing shows a
>    frame preview, and the quality menu offers Auto plus fixed renditions.
> 3. **Rotation and resize** — rotate to fullscreen; cycle fit / fill / zoom.
> 4. **Background and PiP** — swipe up mid-playback; audio should continue, and
>    Picture in Picture should pick up the video.
> 5. **Captions** — turn on CC. Captions appear a beat behind the audio and are
>    written in the device language. (Try changing the device language and
>    reopening a video; the same English audio should caption in the new one.)
> 6. **Downloads** — download a non-live video, then re-open it with the device in
>    Airplane Mode.

---

## App Review Information → Sign-In Required

Tick **Sign-in required** and supply the demo Discord account's email and password.

> **These are the account holder's to enter.** They are Discord credentials for a
> real account and must not be committed to this repository, pasted into a ticket,
> or shared over chat. Enter them directly in App Store Connect.

---

## Review Notes

> Sign-in is through Discord (OAuth2). Tap **Connect to Discord**, sign in with the
> account supplied above, and approve the authorization prompt. You will be returned
> to the app automatically.
>
> The demo account is provisioned on our side to see a small set of sample videos
> rather than the full member catalogue, so the feed will be short — that is
> expected, not an error. Everything else in the app behaves identically.
>
> No account creation, purchase, or subscription is involved, and the app collects no
> personal data beyond the Discord sign-in itself.
>
> Some events are genuinely live and only play while a real event is in progress. If
> a video reports itself unavailable, it has ended — the recorded entries in the feed
> are always playable.


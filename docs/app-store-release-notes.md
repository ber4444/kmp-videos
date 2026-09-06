# App Store public release

Everything App Store Connect asks for before *Apollo Videos* can go on sale to the
public, and the answer this app gives to each.

This is the companion to [testflight-review-notes.md](testflight-review-notes.md),
not a replacement for it. Beta review is a subset of full review: it wants a
description, a "what to test", and a demo account. Full review wants all of that
*plus* an age rating, privacy nutrition labels, a content-rights declaration,
export compliance, pricing and availability, store artwork, and a listing. The
demo account itself is documented once, in the TestFlight notes — it is the same
account here, and its credentials still belong in App Store Connect rather than in
this repository.

Two settings that belong in the *binary* are missing today and cannot be fixed from
the App Store Connect web UI. They are section 0, first, because a build without
them is a build you cannot submit.

---

## 0. Settings that live in the build, not in App Store Connect

`iosApp/Info.plist` is **generated** by `xcodegen generate` from
[`iosApp/project.yml`](../iosApp/project.yml), which silently discards anything not
declared in that spec. Every change below goes in `project.yml`; hand-editing
`Info.plist` survives exactly until the next build.

| Setting | Where | Today | Needs to be |
|---|---|---|---|
| `ITSAppUsesNonExemptEncryption` | `project.yml` → `info.properties` | **absent** | `false` |
| `PrivacyInfo.xcprivacy` | `iosApp/ics_ios/` | **absent** | present, declaring `NSUserDefaults` |
| `CFBundleShortVersionString` | `project.yml` | `1.2` | the public version number |
| `CFBundleVersion` | `project.yml` | `3` | higher than the last build ASC accepted |
| `PRODUCT_BUNDLE_IDENTIFIER` | `project.yml` | `com.livingpresence.inner.circle.squared` | must equal the ASC record and `codemagic.yaml`'s `ios_signing` |
| `TARGETED_DEVICE_FAMILY` | `project.yml` | `1,2` — iPhone **and iPad** | a decision; see below |
| `IPHONEOS_DEPLOYMENT_TARGET` | `project.yml` | `16.0` | drives the "Compatibility" line on the listing |

### Export compliance

Without `ITSAppUsesNonExemptEncryption`, every upload lands in App Store Connect
flagged **Missing Compliance** and cannot be attached to a version until the
question is answered by hand — once per build, forever. The declaration is a
one-line answer to a question this app has an easy answer to: its only cryptography
is the HTTPS/TLS the OS provides and the SHA-256 in the PKCE challenge. Both are
exempt.

```yaml
        # Answers App Store Connect's export-compliance question at upload time
        # rather than parking every build in "Missing Compliance". The only
        # cryptography here is the OS's own HTTPS/TLS and the SHA-256 of the PKCE
        # challenge — both exempt, so no French encryption declaration is needed
        # either.
        ITSAppUsesNonExemptEncryption: false
```

### Privacy manifest

An upload that touches a *required-reason API* without declaring it comes back from
App Store Connect as **ITMS-91053, "Missing API declaration"**. This app touches
one: `NSUserDefaults`, in
[`DiscordSession.ios.kt`](../composeApp/src/iosMain/kotlin/com/livingpresence/inner/circle/squared/discord/DiscordSession.ios.kt) —
the install marker whose whole purpose is to have the *opposite* lifetime to the
Keychain, so a reinstall purges a session the previous owner of the device left
behind. Reason code `CA92.1` is the one that fits: the app reads and writes only
its own data, in its own container.

Nothing else in the iOS source hits the list. `PreviewFrameEngine.ios.kt` uses
`NSFileManager`, but only `URLsForDirectory`, `createDirectoryAtURL` and
`fileExistsAtPath` — none of which are required-reason APIs. No analytics, crash
reporting or advertising SDK is embedded, so no third-party manifest or SDK
signature is in play either.

Create `iosApp/ics_ios/PrivacyInfo.xcprivacy`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>NSPrivacyTracking</key>
	<false/>
	<key>NSPrivacyTrackingDomains</key>
	<array/>
	<key>NSPrivacyCollectedDataTypes</key>
	<array/>
	<key>NSPrivacyAccessedAPITypes</key>
	<array>
		<dict>
			<key>NSPrivacyAccessedAPIType</key>
			<string>NSPrivacyAccessedAPICategoryUserDefaults</string>
			<key>NSPrivacyAccessedAPITypeReasons</key>
			<array>
				<string>CA92.1</string>
			</array>
		</dict>
	</array>
</dict>
</plist>
```

`project.yml`'s target already takes `sources: - path: ics_ios`, and its only
`excludes` entry is `Info.plist`, so the file is picked up as a bundle resource with
no spec change. Confirm it landed — `unzip -l` the IPA and look for
`PrivacyInfo.xcprivacy` at the bundle root — because a manifest that did not get
copied is indistinguishable from one that was never written, until the upload
bounces.

The three `NSPrivacy*` values above are the machine-readable form of the App
Privacy answers in [section 5](#5-app-privacy-the-nutrition-labels). They have to
agree. An empty `NSPrivacyCollectedDataTypes` and a nutrition label claiming
collection (or the reverse) is a contradiction shipped inside the binary.

### Universal binary means iPad is a promise

`TARGETED_DEVICE_FAMILY: "1,2"` ships an app the App Store offers to iPad owners,
which makes two things true at once: **13" iPad screenshots become required**, and
the app has to actually work there — a reviewer will open it on an iPad. The
`UISupportedInterfaceOrientations~ipad` block already anticipates that.

If iPad has not been exercised, `"1"` makes it iPhone-only, drops the iPad
screenshot requirement, and removes a whole surface from review. That is a product
decision, not a technical one, but it should be made deliberately before the first
submission rather than discovered in a rejection.

### A local archive still needs an ExportOptions.plist

The README's `xcodebuild -exportArchive` command passes
`-exportOptionsPlist ExportOptions.plist`, and no such file is in the repository —
a local archive stops there. CI is unaffected: `codemagic.yaml` uses
`xcode-project build-ipa`, which writes its own export options. Only relevant if
the release build is ever produced by hand.

---

## 1. App Store Connect → App Information

Platform-level fields, shared by every version of the app.

| Field | Value | Notes |
|---|---|---|
| **Name** | `Apollo Videos` | 30 characters max. Matches `CFBundleDisplayName` and Android's `android:label`. |
| **Subtitle** | `Live and recorded event video` | 30 max; this is 29. Shown under the name in search results. |
| **Bundle ID** | `com.livingpresence.inner.circle.squared` | Same as Google Play and `project.yml`. |
| **SKU** | your own string, e.g. `apollo-videos-ios` | Never shown to users; cannot be changed later. |
| **Primary language** | English (U.S.) | |
| **Privacy Policy URL** | `https://ber4444.github.io/kmp-videos/privacy/` | Required. The same page Google Play links to, published from [`docs/privacy/`](privacy/). |
| **Category (primary)** | Entertainment | |
| **Category (secondary)** | Photo & Video | Optional, and the honest second fit for a player. |
| **Content Rights** | *Contains third-party content* → **Yes** | See below. |
| **Age Rating** | see [section 4](#4-age-rating) | |
| **License Agreement** | Apple's standard EULA | There is no custom EULA; do not invent one. |
| **Localizations** | English (U.S.) only | |

**Content rights.** The app streams concert and event video, which is third-party
content by App Store Connect's definition even though it comes from the community's
own server. Answer *yes*, then confirm you have the rights to it — the recordings
are the Apollo community's own event recordings, served from its own Wowza server,
and the app displays nothing it was not given an address for. Do not answer *no*
just because nothing is licensed from a studio; the question is about content the
app displays that the developer did not create.

**Localizations.** Live captions are written in the device's language, because
Soniox translates in-band — that is a *runtime* behaviour and not a store
localization. The app's own UI is English. Adding an App Store localization commits
you to a translated description *and a translated screenshot set*, so add none until
the UI itself is translated.

---

## 2. Pricing and Availability

| Setting | Value | Why |
|---|---|---|
| **Price** | Free | There is no purchase anywhere in the app. |
| **Availability** | All countries and regions | Unless the EU trader declaration below changes your mind. |
| **Pre-orders** | Off | |
| **Mac (Apple Silicon)** | **Off** | Default is on for iPad-capable apps. Never built or run there; AVPlayer + PiP behaviour and the `discord-<APP_ID>` URL-scheme redirect are all untested on macOS. A crash on Mac is a rejection *and* a one-star review. |
| **Apple Vision Pro** | **Off** | Same reason. "Compatible" is not the same as tested. |
| **Distribute on the App Store** | On | |
| **Custom / volume / education distribution** | Off | |

**EU trader status.** Distributing in the EU requires a trader-status declaration in
App Store Connect's business settings, and for anyone declaring as a trader, the
verified name, address, phone number and email are **published on the App Store
product page** in EU storefronts. For an individual developer without a business
address, that is a home address on a public web page. This is the account holder's
call, and it has exactly two outcomes: publish the contact details, or exclude the
EU storefronts under *Availability*. Check the current wording in App Store Connect
before answering — this requirement has been revised more than once.

---

## 3. The version page — "1.2 Prepare for Submission"

### Screenshots

Required, and a submission is blocked without them. Because the binary is universal
(section 0), **both** sets are mandatory:

| Device set | Size | Count |
|---|---|---|
| iPhone 6.9" | 1320 × 2868 or 1290 × 2796 | 1–10, at least 1 |
| iPad 13" | 2064 × 2752 or 2048 × 2732 | 1–10, at least 1 |

One iPhone set is scaled down for smaller iPhones, so a single 6.9" set covers the
phone lineup. Shoot six, in this order — it is the same tour as the TestFlight
"What to Test", which is not a coincidence:

1. The landing screen with *Connect to Discord*.
2. The feed, with a LIVE badge and duration labels visible.
3. Playback with the controls showing.
4. The quality menu open over the video (Auto plus the fixed renditions).
5. Captions on, in a non-English device language — this is the feature nothing else
   in the category does, and it is invisible in a still unless you show it.
6. A downloaded video playing with the device offline.

Shoot them from the **demo account**, not a member account. A screenshot of the full
member catalogue advertises a feed no new user can reach, and the reviewer comparing
screenshots to what the demo account sees is a metadata rejection waiting to happen.

**App previews** (video) are optional. Skip them for the first release; a
30-second capture of the resize matrix and jump-to-live is a good later addition.

**App icon** comes from `Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` — the
golden bark on white, already in the repository and matching Android's adaptive
icon. Nothing to upload separately.

### Text fields

**Promotional text** (170 max — editable any time, without a new build):

> Live captions in your own language, an adaptive quality ladder, DVR rewind on
> live events, and offline downloads. Apollo's event video, on your phone.

**Description** (4,000 max). Longer than the TestFlight beta description, and
written for someone deciding whether to install rather than for a reviewer:

> Apollo Videos streams live and recorded event video from the Apollo community's
> own server.
>
> **A feed that tells you what is happening now.** Every event in the gallery shows
> a poster frame, how long it runs, and whether it is live at this moment. Live
> events carry a LIVE badge and can be joined mid-broadcast.
>
> **Playback that adapts to you.** Quality follows your connection *and* the size
> the video is actually being drawn at, so a phone held upright never downloads a
> stream sized for a television. You can override it at any time and pin a fixed
> rendition, or drop to audio-only to save data.
>
> **Live events you can rewind.** Pause a live event, scrub back through the
> server's DVR window, and jump back to the live edge in one tap. The seek bar
> tracks a window that is still growing, without drifting out from under you.
>
> **Captions in your language.** Switch captions on during playback and the spoken
> English is transcribed and translated as it happens, into whatever language your
> device is set to. No second app, no delay between the audio and the words.
>
> **Watch it your way.** Fill, fit or zoom the picture, rotate to fullscreen, and
> keep listening when you leave the app — audio continues in the background, and
> Picture in Picture keeps the video on screen while you do something else.
> Vertical video is framed properly rather than pillarboxed into a stamp.
>
> **Take it offline.** Download any recorded event and watch it with no connection
> at all. Downloads continue while the app is in the background.
>
> **Private by construction.** No analytics, no advertising, no tracking, no account
> to create. Nothing about you is collected or stored by the developer. The app is
> open source, so every word of that can be checked against the code.
>
> Apollo Videos is for members of the Apollo Discord community. The app opens on a
> sign-in screen, and access is granted by your Discord membership.

**Keywords** (100 characters, comma-separated, no spaces — and never repeat the app
name or the category name, which are already indexed):

```
live,stream,concert,event,video,player,hls,captions,subtitles,offline,dvr,picture in picture,replay
```

**Support URL** — required, and it must resolve to a page that offers help:
`https://github.com/ber4444/kmp-videos/issues`

**Marketing URL** — optional: `https://github.com/ber4444/kmp-videos`

**Copyright** — `2026 Gabor Berenyi`

**What's New in This Version** — not shown for a first release; required for every
update after it.

### Release options

| Setting | Value | Why |
|---|---|---|
| **Version Release** | Manually release this version | Approval and publication become two separate moments. Worth having: a reviewer can only ever see recorded demos, but a launch can be timed to a real live event. |
| **Phased Release for Automatic Updates** | n/a on a first release; **on** afterwards | Seven-day ramp, with a pause button if something is wrong. |
| **Reset iOS app ratings** | Off | |

---

## 4. Age rating

The questionnaire is answered about **the content the app streams**, not about the
app's chrome — and its wording is revised periodically, so answer the question in
front of you rather than the one transcribed here.

| Category | Answer | Note |
|---|---|---|
| Violence (cartoon, fantasy, realistic) | None | |
| Sexual content or nudity | None | |
| Profanity or crude humor | **depends on the catalogue** | Recorded live speech. Only the account holder knows. |
| Alcohol, tobacco, or drug use or references | **depends on the catalogue** | Same. |
| Horror or fear themes | None | |
| Mature or suggestive themes | **depends on the catalogue** | |
| Gambling, simulated or real | None | |
| Contests | None | |
| Medical or treatment information | None | |
| Unrestricted web access | **No** | The only navigation out of the app is Discord's authorization URL in Safari — a fixed endpoint, not a browser. |
| User-generated content | **No** | See [section 7](#7-rejections-to-expect-and-the-answer-to-each). |
| Messaging or chat between users | None | The app has no messaging surface at all. |
| Advertisements | None | |

If the catalogue is clean, this lands at **4+**. The three "depends" rows are the
whole question, and they are not ones the repository can answer — someone has to
watch what is actually in the feed. An age rating that undersells its content is one
of the easier ways to be pulled from sale *after* approval, which is worse than
being rejected before it.

---

## 5. App Privacy (the nutrition labels)

**Declaration: Data Not Collected.** Tracking: **No** — and therefore no
`NSUserTrackingUsageDescription` in `Info.plist` and no ATT prompt. Do not add
either; an unused purpose string invites a question you have no reason to answer.

That declaration is not a shortcut, and it holds up destination by destination.
Apple's bar for "collected" is data transmitted off the device and retained beyond
what serving the request needs:

| Destination | What it receives | Why it is not collection |
|---|---|---|
| The Wowza streaming server | Requests for the playlists and segments you chose to play | Media requests. Nothing about the user. |
| Discord | The standard OAuth2 exchange the user starts by tapping *Connect to Discord*; scopes `identify` and `guilds` only | The user's own account with a third party, authenticated on Discord's pages. The app never sees the password, never requests email. The guild list is examined in memory for one yes-or-no and discarded; the display name is written to the Keychain **on the device** and sent nowhere. |
| `gist.githubusercontent.com` | A request for a text file of extra videos | Carries nothing about the user. |
| `:server` (the developer's own) | The Discord access token, on the caption-key and feed-policy routes | Used to ask Discord one question — is this account admitted — then discarded. It stores no records. |
| Soniox | The audio track *of the video being watched*, while captions are on | That is media from the streaming server, not the user. The app holds no microphone permission and cannot record anyone. |

Two things a careful reader will find, both of which should be known before they are
raised rather than after:

- **`:server` caches per token for five minutes.** That is a real-time service cache
  keyed by a bearer token — it exists so a caption session does not re-ask Discord
  on every reconnect — not a record of a person. It is still worth being able to say
  out loud.
- **No third-party SDK is embedded in the binary**, so nothing collects behind your
  back. This is the part most "Data Not Collected" claims fail on, and it is why
  `NSPrivacyCollectedDataTypes` in the privacy manifest can honestly be empty.

Three statements have to agree, and all three are in this repository:

1. `PrivacyInfo.xcprivacy` (section 0),
2. this nutrition-label declaration,
3. [`docs/privacy/index.html`](privacy/index.html), which is also what the Play
   Data Safety form's *No data collected* rests on.

**If a reviewer pushes back**, the fallback is *Identifiers → User ID*, purpose *App
Functionality*, **not** linked to identity, **not** used for tracking — on the
grounds that a Discord token reaches the developer's server at all. That is a
form edit, not a new build, so it costs a resubmission of metadata rather than a
release. Do not volunteer it; the accurate answer is the first one.

---

## 6. App Review Information

**Sign-in required:** yes. Same demo Discord account as beta review — see
[testflight-review-notes.md](testflight-review-notes.md#app-review-information--sign-in-required).
The credentials are the account holder's to type into App Store Connect, and belong
in neither repository nor chat.

**Contact information:** first name, last name, phone, email — a reachable human, as
this is where a rejection arrives.

**Notes** (copy-paste):

> **Signing in.** Access is limited to members of the Apollo Discord community, so
> the app opens on a sign-in screen. Tap **Connect to Discord**; the app opens
> Discord's authorization page **in Safari**, where you sign in with the demo account
> supplied above and approve the prompt. You are returned to the app automatically.
> If the Discord app happens to be installed on the review device, the link may open
> there instead — signing in inside the Discord app works the same way.
>
> **What the demo account sees.** It is provisioned on our side with a small set of
> sample recordings rather than the full member catalogue, so the feed is short by
> design. Everything else in the app behaves identically.
>
> **Live events.** Some entries are genuinely live and only play while a real event
> is in progress. If one reports itself unavailable, it has ended. The recorded
> entries are always playable, and every feature below can be exercised on them.
>
> **Background audio and Picture in Picture.** The app declares the `audio`
> background mode and uses it: start a video, then swipe up to the Home Screen.
> Audio continues, and Picture in Picture picks up the video. This is core to the
> app — events run for hours and are commonly listened to rather than watched.
>
> **Captions.** Switch CC on during playback, then seek to **2:34**. The demo video
> is silent until then, so captions turned on earlier correctly show nothing; text
> appears once the speech starts, written in the device's language. Set the device
> to a non-English language to see the in-band translation.
>
> **Downloads.** Download a recorded (non-live) event, then re-open it with the
> device in Airplane Mode.
>
> **No purchases, no account creation.** The app contains no in-app purchases,
> subscriptions, or paid content, and creates no account of its own — sign-in is to
> the user's existing Discord account. The app collects no data; see the privacy
> policy at https://ber4444.github.io/kmp-videos/privacy/, and the full source at
> https://github.com/ber4444/kmp-videos.

**Attachment:** optional. A short screen recording of the sign-in and of background
audio is the cheapest insurance against a reviewer who cannot get past the gate.

---

## 7. Rejections to expect, and the answer to each

### 4.8 — Login Services

**The real risk on this app.** Discord is the only way in, and there is no Sign in
with Apple.

The prepared answer: Apollo Videos is a client for a specific third-party service,
and the user signs in to their own Discord account to reach content that lives
behind that community's membership. The sign-in is not an account-creation
convenience layered over a general-purpose app — **it is the authorization check
itself**: `:server` answers `GET /v1/feed/policy` with the stream addresses for a
member and `403` for everyone else, so a non-member is not shown an empty app, they
are not told where the streams are at all. The app requests the two narrowest scopes
that answer the question, `identify` and `guilds`, and never asks for email.

Be ready for this to be the one that actually blocks the release, because the
fallback is not a code change of any size: Sign in with Apple cannot answer "is this
person on the Apollo server", so adding it would produce a sign-in that grants
access to nothing. If Apple insists, that is a conversation about the product, not a
patch.

### 2.1 — App Completeness (the demo account)

A reviewer who cannot get past the gate rejects the build, and this gate fails
closed on purpose. Before submitting, verify on a real device that the demo account
still gets in: the reviewer's Discord **snowflake** must be in `TEST_USER_IDS` and
`DEMO_VIDEOS_URL` must be set, or the account is refused with `403` — a
half-applied config refuses rather than falling through to the membership check
(that property is covered by `FeedPolicyRouteTest`, and it is deliberate). Both are
`fly secrets` on `apollo-videos-tokens`; see
[server/README.md](../server/README.md#feed-policy).

### 5.1.1(v) — Account deletion

The rule applies to apps that let users *create* an account. This app creates none —
it authenticates an account the user already has at Discord — so the in-app deletion
requirement does not attach.

Two loose ends worth closing before someone else finds them. **There is no in-app
sign-out**: the stored session is cleared only when Discord rejects the refresh
token or the account is refused, and no UI calls `sessionStore.clear()`. Meanwhile
[the privacy policy](privacy/index.html) says "Signing out erases the stored
session". One of those two should change. If the question is asked before it is
fixed, the true answer is that deleting the app erases the session — the first-run
Keychain purge in `DiscordSession.ios.kt` makes that true even though iOS keeps
Keychain items across an uninstall — and the Discord account itself is managed at
discord.com.

### 1.2 — User-generated content

A reviewer who sees a Discord gate may assume there is a community feed behind it.
There is not. The catalogue is curated server-side — numbered events probed on the
Wowza server plus a manifest the operator maintains — and the app has no upload, no
comments, no ratings and no messaging. There is no surface through which one user
can show another user anything, so the UGC obligations (filtering, reporting,
blocking, published contact) have nothing to attach to.

### 5.2 — Intellectual property

See the content-rights declaration in section 1. The recordings are the Apollo
community's own event recordings on its own server.

### 2.5.4 — Background modes must be used

`UIBackgroundModes: [audio]` is declared, and Apple checks that an app declaring it
genuinely plays audio in the background. It does, and the review notes above tell
the reviewer exactly how to see it. Nothing to fix; just do not remove that
paragraph from the notes.

### 3.1.1 — In-app purchase

The app sells nothing, unlocks nothing for money, and links to no purchase. If
Apollo membership ever becomes something people pay for anywhere, this stops being a
non-question — flag it then. Today the honest answer is that there is no purchase in
or around the app.

### 2.3 — Accurate metadata

The screenshots must be of this app, showing what a new user can actually reach.
Shoot them from the demo account (section 3).

---

## 8. Pre-submission checklist

- [ ] `ITSAppUsesNonExemptEncryption: false` added to `project.yml`
- [ ] `PrivacyInfo.xcprivacy` added, and verified present in the IPA with `unzip -l`
- [ ] `CFBundleShortVersionString` / `CFBundleVersion` bumped in `project.yml`
- [ ] iPad decision made — universal with 13" screenshots, or `TARGETED_DEVICE_FAMILY: "1"`
- [ ] Build installed from TestFlight on a real iPhone *and*, if universal, a real iPad
- [ ] Demo account verified end-to-end on that build, on a device that has never signed in
- [ ] Background audio and PiP confirmed working on the exact build being submitted
- [ ] Screenshots captured from the demo account, both required sizes
- [ ] Privacy Policy URL resolves: <https://ber4444.github.io/kmp-videos/privacy/>
- [ ] Support URL resolves
- [ ] Age-rating "depends on the catalogue" rows answered by someone who has watched it
- [ ] App Privacy answers match `PrivacyInfo.xcprivacy` and the privacy policy page
- [ ] Content-rights declaration answered *yes* and confirmed
- [ ] Mac (Apple Silicon) and Apple Vision Pro availability switched **off**
- [ ] EU trader status decided
- [ ] Sign-in credentials and contact info entered in App Review Information
- [ ] Review notes pasted (section 6)
- [ ] Version Release set to **Manually release this version**

# Admin Panel

A single static file (`index.html`) - no build step, no server. It writes
announcement/update popups to Firebase Realtime Database, which the Android
app reads and shows to users at most once per day (see
`AnnouncementManager.kt`).

## Hosting it

Any static host works. Two easy options with this repo:

- **Firebase Hosting** (already configured via `firebase.json`, `public` folder):
  ```
  firebase deploy --only hosting
  ```
  The panel will be live at `https://<your-project>.web.app/admin/`.

- **GitHub Pages**: in your GitHub repo settings -> Pages, set the source to
  the `public` folder (or copy `public/admin/index.html` to `/docs/admin/index.html`
  and point Pages at `/docs` if your repo's Pages setup expects that
  convention). The panel will be live at
  `https://<your-username>.github.io/<repo>/admin/`.

## Before you deploy

1. Open `index.html` and change `ADMIN_PASSWORD` to something private. This
   client-side password is just a casual-visitor gate, not real security -
   see the next step for the part that actually matters.
2. In the [Firebase console](https://console.firebase.google.com) ->
   Realtime Database -> Rules, make sure `announcements` allows public read
   (so the app can fetch it with no login) but only authenticated writes
   (so random visitors can't push their own announcements even if they find
   this page):
   ```json
   {
     "rules": {
       "announcements": {
         ".read": true,
         ".write": "auth != null"
       }
     }
   }
   ```
   The panel signs in anonymously (via Firebase Auth) before writing, which
   satisfies `auth != null`. For stronger protection, switch the panel to
   real email/password sign-in and restrict `.write` to specific admin UIDs.

## How it works

- Sending an announcement writes to `announcements/current` (what the app
  reads) and `announcements/history/{id}` (an audit trail the app never
  reads).
- The app (`AnnouncementManager.kt`) checks `announcements/current` once per
  launch and shows it as a popup if it's `active` and hasn't already been
  shown today on that device.
- "Deactivate" flips `active` to `false` so the app stops showing it, without
  deleting the record.


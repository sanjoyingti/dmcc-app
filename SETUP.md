# 26-DMCC — setup guide

App: **26-DUARBAGORI MANDAL CONGRESS COMMITTEE (26-DMCC)** · package `com.dmcc26.app`
Developed by Sanjoy Ingti · Ph: 7002397372

This app has its own GitHub repo and its own Firebase project, separate from your other apps.

## 1. Create the Firebase project (free)
1. Open https://console.firebase.google.com → **Create a project** → name it `dmcc26` → Continue (Google Analytics can be off).
2. On the project page tap the **Android** icon (Add app).
   - Package name: `com.dmcc26.app`
   - App nickname: `26-DMCC`
   - Debug signing certificate SHA-1: `79:68:EB:8C:1A:B8:53:CC:0A:FD:32:88:1B:B9:A2:57:67:CE:52:C6`
   - Tap **Register app**, then **Download google-services.json**. Skip the remaining steps.
3. Project settings (gear icon) → Your apps → `26-DMCC` → **Add fingerprint** → paste the SHA-256:
   `27:03:22:E7:4D:ED:B5:D8:11:6B:24:82:26:CD:A4:76:8A:A9:25:8D:A3:04:93:B1:DB:17:21:44:F1:02:7F:8E`

## 2. Turn on login
Build → **Authentication** → Get started → Sign-in method:
- **Phone** → Enable → Save.
- **Email/Password** → Enable → Save.
(On the free plan Firebase allows only a small number of OTP SMS per day. For many users, upgrade to the Blaze plan; it has a free allowance.)

## 3. Create the database
Build → **Firestore Database** → Create database → choose location **asia-south1 (Mumbai)** → Start in **production mode**.
Then open the **Rules** tab, delete everything, paste the whole content of `firebase/firestore.rules` from this repo, and tap **Publish**.

## 4. Put the config in the repo and build
In this GitHub repo: **Add file → Upload files** → upload `google-services.json` into the `app` folder
(the file name must be `app/google-services.json`) → Commit.
GitHub Actions builds the APK automatically (about 10 minutes). Download it from **Releases** → `26-DMCC.apk`.

## 5. Optional extras
- **Calls/notifications while the app is closed:** needs the Blaze plan. Install Firebase CLI on a computer and run
  `cd firebase && firebase deploy --only functions` (pick this project).
  Without this, calls and messages notify while the app is open/in recent apps, and notices are checked every ~15 minutes.
- **Calls that connect but have no sound/video on some mobile networks:** add a TURN server. In Firestore create
  collection `config`, document `app`, fields `turnUrl`, `turnUser`, `turnPass` (strings).

## Updating the app later
Change code → push to this repo → a new release is built. In the app, **More → Check for updates** downloads the newest APK.
Install it over the old one (same signing key, so data and login stay).

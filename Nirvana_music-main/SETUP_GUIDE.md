# Harmonix — Setup & GitHub Guide

Yeh guide batati hai ki project mein kya gadbad thi, kya fix kiya gaya, GitHub pe kaise upload karna hai, aur API key kahan lagti hai.

## 1. Jo file corrupt/missing thi (fixed)

**`app/src/main/res/drawable/ic_music_logo_1783342219708.jpg` — 0 bytes (khaali/corrupt file).**

Yeh file app ke adaptive launcher icon ke foreground layer (`ic_launcher_foreground.xml`) se reference ho rahi thi. Ek khaali/invalid image file ko Android ka resource compiler (`aapt2`) build ke time crash kar deta — yaani **build hi fail ho jaata**, chahe local machine pe ho ya GitHub Actions pe.

**Fix:** Maine is jpg ko delete karke ek naya vector drawable (`ic_music_logo.xml`) banaya — app ke brand color (neon green) mein ek simple music-note icon — aur `ic_launcher_foreground.xml` ko usse point kar diya. Ab launcher icon bhi sahi dikhega aur build bhi nahi tootegi.

Baaki project (Kotlin code, manifest, Room DB, navigation, ViewModels) structurally theek tha — koi aur missing/broken file nahi mili.

## 2. Jo cheezein "missing" hain but jaan-boojh kar hain (aapko khud add karni hongi)

Yeh sab `.gitignore` mein hain isliye zip/export mein nahi aatin — normal baat hai:

| File | Kis liye chahiye | Kab chahiye |
|---|---|---|
| `.env` (root mein) | `GEMINI_API_KEY` rakhne ke liye | Agar aap Gemini/Firebase AI feature use karna chaho |
| `debug.keystore` | Debug APK sign karne ke liye | Local build / CI dono ke liye |
| `my-upload-key.jks` + `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` env vars | Release (Play Store wali) signed APK/AAB | Sirf tab jab production release banani ho |
| `google-services.json` | Firebase | Optional — `googleServices.missing.passthrough=true` set hai, isliye iske bina bhi build chalegi (bas Firebase features kaam nahi karenge) |

**GitHub Actions workflow (`.github/workflows/android-build.yml`) mein maine debug.keystore CI ke andar hi generate karwa diya hai** (`keytool` se), isliye debug APK build karne ke liye aapko kuch extra karne ki zaroorat nahi — bas push karo, APK apne aap ban jaayegi.

## 3. "API kidhar lagega?" — GEMINI_API_KEY

Is project mein asli "API" jo integrate hoti hai woh **Gemini API key** hai (Google AI Studio se generated apps isi convention ko follow karte hain).

- **Local development (Android Studio) ke liye:** root folder mein `.env` naam ki file banao (jaisa `.env.example` mein hai), aur likho:
  ```
  GEMINI_API_KEY=your_real_key_here
  ```
  Secrets Gradle Plugin ise automatically `BuildConfig`/resources mein inject kar deta hai. `.env` file `.gitignore` mein hai, kabhi bhi GitHub pe commit nahi hogi — yeh sahi practice hai.

- **GitHub Actions (CI) ke liye:** repo ke **Settings → Secrets and variables → Actions → New repository secret** mein jaake `GEMINI_API_KEY` naam se secret add karo. Workflow file usse automatically `.env` mein likh dega build se pehle. Agar secret set nahi karoge, build fail nahi hogi — bas placeholder key use hogi (jo real Gemini calls ke liye kaam nahi karegi).

- **Abhi ke code mein iska real use nahi hai:** `firebase-ai` dependency add hai lekin code mein kahin call nahi ho rahi. Toh filhaal key na ho to bhi app chalega, bas future AI features (e.g. smart Jam replies) ke liye ready rakha gaya hai.

- **Release build sign karne ke liye extra secrets** (sirf tab chahiye jab Play Store ke liye signed APK/AAB banani ho): `KEYSTORE_BASE64` (jks file ko base64 karke), `STORE_PASSWORD`, `KEY_PASSWORD` — inhe bhi GitHub Secrets mein add karke workflow mein decode/use karwaya ja sakta hai. Filhaal workflow sirf **debug APK** banata hai jisme yeh zaroorat nahi.

## 4. GitHub pe repository kaise banayein aur push karein

Main directly aapke GitHub account mein repo nahi bana sakta (uske liye connection/authentication chahiye), lekin yeh steps follow karo:

1. https://github.com/new pe jaake naya repository banao (e.g. `harmonix-music-app`), **README/gitignore add na karo** (kyunki already hain).
2. Apne computer pe terminal khol ke:
   ```bash
   cd path/to/this/project
   git init
   git add .
   git commit -m "Initial commit: Harmonix music app"
   git branch -M main
   git remote add origin https://github.com/<your-username>/harmonix-music-app.git
   git push -u origin main
   ```
3. Push hote hi **Actions tab** mein workflow apne aap chalega (`android-build.yml`).
4. Workflow complete hone ke baad, us run ko open karo → neeche **Artifacts** section mein `harmonix-debug-apk` milega — wahan se APK download kar sakte ho.

Agar chaho to mujhe GitHub connector (jab prompt aaye) connect karke bhi bol sakte ho — phir main seedha commits/push kar sakta hoon is chat se.

## 6. Jam (real cross-device group listening + live chat) — Firebase setup required

Jam ab **local simulation nahi hai** — real Firebase Realtime Database use karta hai taaki do alag phones same room mein sync ho sakein (playback + chat dono). Isko kaam karne ke liye ek **real Firebase project** chahiye:

1. https://console.firebase.google.com pe naya project banao.
2. Android app add karo, package name: `com.aistudio.harmonixmusic.vkzpnb` (yeh `applicationId` hai `app/build.gradle.kts` mein).
3. `google-services.json` download karke project ke **`app/`** folder mein daal do (root mein nahi, `app/` ke andar).
4. Firebase console mein:
   - **Build → Authentication → Sign-in method** mein **Anonymous** enable karo (JamManager anonymous sign-in use karta hai, koi login form nahi chahiye).
   - **Build → Realtime Database** banao (kisi bhi region mein), aur **Rules** ko yeh set karo (testing ke liye — production ke liye tighten karna):
     ```json
     {
       "rules": {
         "jams": {
           ".read": "auth != null",
           ".write": "auth != null"
         }
       }
     }
     ```
5. `google-services.json` bhi `.gitignore` mein add kar dena chahiye agar aap isse public repo mein nahi rakhna chahte (abhi yeh add nahi hai — agar repo private hai to koi baat nahi, warna add kar do).

**Bina google-services.json ke bhi build fail nahi hogi** (`missingGoogleServicesStrategy = WARN`), lekin Jam feature runtime pe crash/fail karega jab tak yeh setup nahi hota.

### Jam mein kya real hai ab:
- **Create Jam** → ek room code milta hai (jaise `NIR482`) jo doosre device se share kar sakte ho.
- **Join Jam** → code daal ke same room mein connect ho jaate ho — dono devices ka song, play/pause, aur seek position **real-time sync** hote hain (Firebase ke through).
- **Chat** → real-time messaging, typing indicator, aur emoji reactions — sab Firebase se live sync hote hain, kisi bhi participant ka message turant doosre device pe dikhta hai.
- Reply-to aur reactions bhi Firebase mein store hote hain (single-device simulation nahi raha).

### Jaan-boojh kar simplify kiya gaya:
- Message ka "read receipt" status (SENT/DELIVERED/READ tick icon) abhi hamesha SENT dikhata hai — per-participant read-tracking add nahi ki (low value ke liye bahut zyada Firebase writes hoti, agar chahiye to bata dena, add kar dunga).

## 6b. Google Sign-In Login — koi backend/Firebase nahi chahiye, 100% free

Login ab **native "Sign in with Google"** hai (Android ka Credential Manager) — koi email link, koi Firebase Auth, koi App Links setup nahi. Google account picker seedha on-device dikhta hai, ek tap mein login ho jaata hai. Ye purani Firebase email-link wali problem (link browser mein khulna, expire ho jaana, cross-device mismatch) permanently fix karta hai kyunki ab poora flow on-device hai — koi email/deep-link involve hi nahi.

### Setup — kuch bhi extra nahi karna
`google-services.json` mein already ek Web OAuth client (`645739166879-r25846lbjpje0tgsdf2kaaddk2slspoe.apps.googleusercontent.com`) aur Android OAuth client (debug keystore ke SHA-1 se linked) maujood hain — ye pehle se Firebase project ke saath set ho chuke the, isliye Google Sign-In turant kaam karega, koi naya console setup nahi chahiye.

**Bas ek cheez check karo:** agar aap apna khud ka keystore/signing key use kar rahe ho (naya `DEBUG_KEYSTORE_BASE64` ya release keystore), to uska SHA-1 bhi Firebase console mein add karna hoga:
1. https://console.firebase.google.com → apna project (`nirvanamusic-75348`) → **Project settings (⚙️) → General tab → apna Android app card → "Add fingerprint"**.
2. Naya SHA-1 add karo, `google-services.json` dobara download karke `app/` folder mein replace karo.

### Test karo
1. APK install karo, app kholo → "Continue with Google" dabao.
2. Google account picker aayega — apna account choose karo. Bas, login ho gaya (koi email check karne ki zaroorat nahi).

### Jaan-boojh kar simplify kiya gaya:
- Koi backend/server-side session nahi hai — login sirf is device par "kaunsa Google account use ho raha hai" track karta hai (SharedPreferences mein), display ke liye. Kisi bhi remote resource ko ye gate nahi karta.
- JamManager ka Firebase Anonymous Auth isse alag hai — wo sirf Realtime Database ki "auth != null" security rule satisfy karne ke liye internally chalta hai, user ko kabhi dikhta hi nahi. Usse chhua nahi gaya hai.

## 7. YouTube full songs + video Samples — done

**Home Search** ab YouTube Data API v3 use karta hai (`YouTubeService.kt`) — search karke real duration ke saath full song results deta hai. Tap karke play karo to woh **YouTube ke official chhote embedded player** (WebView, bottom-right corner, ~72dp) mein bajta hai — sirf tab visible/mounted hota hai jab koi YouTube track active ho. Yeh isliye kyunki YouTube Data API sirf metadata deta hai, direct stream URL nahi — aur stream extract karke apne player mein daalna YouTube ke ToS ke against hai, isliye official chhota visible player use kiya (aapne yehi option choose kiya tha).

**Samples** ab iTunes ka `musicVideo` entity use karta hai — asli ~30-second **video** preview (VideoView se render hota hai), audio-only nahi.

**"Play Full Song" button** (Samples mein) — ab external YouTube app/browser nahi kholta. Yeh humara apna button hai: title+artist se best-matching YouTube video dhoondta hai (`findBestYouTubeMatch`) aur usi chhote in-app player mein play kar deta hai.

### Zaroori:
- `YOUTUBE_API_KEY` GitHub secret already add kar diya hai aapne — workflow use `.env` mein likh dega build ke waqt.
- Local development ke liye bhi `.env` mein `YOUTUBE_API_KEY=...` add karna hoga (`.env.example` mein reference hai).
- Google Cloud Console mein "YouTube Data API v3" enabled honi chahiye jis project ke liye key banayi hai.

### Jaan-boojh kar simplify kiya gaya:
- Samples ka "preload next video" feature hata diya (pehle audio ke liye tha) — ab sirf currently-visible page hi video decode karta hai, taaki multiple simultaneous video decoders na chalein. Swipe karne pe halka sa loading dikh sakta hai, jo pehle instant tha.

## 8. Workflow kya karta hai (summary)

- Push / PR / manual "Run workflow" pe trigger hota hai.
- JDK 17 + Gradle 9.3.1 setup karta hai (project ka wrapper nahi hai, isliye Gradle directly install hota hai — AGP 9.1.1 ko minimum Gradle 9.3.1 chahiye).
- Debug keystore generate karta hai.
- Zaroori Android SDK platform/build-tools (36 / 36.0.0) install karta hai.
- Agar `GEMINI_API_KEY` secret set hai to `.env` bana deta hai.
- `gradle assembleDebug` chalata hai.
- Final `.apk` ko Actions artifact ke roop mein upload kar deta hai.

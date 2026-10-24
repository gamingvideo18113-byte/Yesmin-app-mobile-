# Yesmin: Android voice assistant (Lia jaisa)

Gemini Live se insaan jaisi awaaz, 24 ghante chalu mic, screen ke kinare ki glow line, aur AccessibilityService se phone control.

## Kya-kya hai (is version mein)
- **Voice engine:** foreground service (microphone), wake lock, echo-cancelled mic jo bolte waqt bhi khula rehta hai, Gemini Live streaming, beech mein bolne par Yesmin ruk jati hai, connection tootne par bina mic roke reconnect, watchdog.
- **31 Gemini voices** ka picker (▶ se sunkar chunein), personality, emotion slider, language, response style.
- **API key Save + Test:** key clean hoti hai (space/quotes hatate hain), commit se save hoti hai, turant Google se check hoti hai aur saaf message milta hai.
- **Models auto-detect:** Live aur TTS model aapki key ke hisaab se apne aap milte hain (naam badalne par code nahi chhedna).
- **Phone control tools (Gemini function calling):** app kholna, website kholna, Google search, screen padhna, tap (text ya x,y), long press, swipe, scroll, type, back/home/recents/notifications/quick settings/lock/screenshot/power menu, wait, volume, media keys, brightness, torch, contacts, call/end/answer, SMS, alarm, timer, Settings pages (Wi-Fi panel, Bluetooth, ...), battery/status.
- **Safety:** banking/payment/password apps par automation band, password field mein type nahi, call/SMS se pehle confirmation, dry-run mode, Emergency Stop (notification ka Stop + orb button), action log.
- **Yaaddaasht:** Yesmin `remember()` se aapke baare mein facts yaad rakhti hai, har session mein use karti hai.
- **Edge glow:** screen ke kinare ki chamakti line, rang halat se badalta hai (listening teal, thinking purple/yellow, speaking pink).
- **Permission Center:** ek jagah sab permissions, "Baaki sab ek ke baad ek kholo" wizard.
- **Auto-update:** GitHub Releases se naya APK check, download, install.
- Text chat (tools ke saath), Action log.

## Build (sabse aasan: GitHub Actions)
1. GitHub par **private** repo banao aur is folder ka sab kuch upload/push karo (signing key `app/yesmin.jks` isme hai, isliye private rakho).
2. Repo > **Actions** > "Build APK" > Run workflow.
3. Khatam hone par Artifacts se `yesmin-debug-apk` download karo, zip kholo, APK install karo.
4. Har build ek hi key se sign hota hai, isliye naya APK purane ke upar update ho jata hai.

Phone par Termux se build bhi ho sakta hai, par Android SDK/aapt2 ka setup aksar atakta hai; GitHub Actions bharosemand rasta hai.

## Pehli baar setup
1. Install karne ke baad app kholo > Settings > **Gemini API key** paste karo > **Save + Test** (hara ✓ aana chahiye). Free key: aistudio.google.com/apikey
2. Settings > **Permission Center**: Microphone, Notifications, Accessibility, Display over other apps, Battery Unrestricted, aur jo chahiye (Contacts, Phone, SMS).
3. Android 13+ par Accessibility grey ho to: App info > upar teen dots > "Allow restricted settings", phir Accessibility mein Yesmin on karo.
4. Neeche wale orb ko dabao. Mic hamesha chalu rahega (notification mein Mute/Stop).
5. Xiaomi/Vivo/Oppo/Samsung par: Battery > Yesmin > **Unrestricted** / Autostart on, warna company ki setting mic band kar degi.

## Auto-update
Settings > Updates mein `https://api.github.com/repos/NAAM/REPO/releases/latest` daalo. Naya APK GitHub Release mein attach karo (version tag jaise `v1.0.1`, aur `versionName` `app/build.gradle.kts` mein badlo). Android silently install nahi karne deta, isliye install par aapko ek baar "Install" dabana hoga.

## Dhyan rakhne wali baatein
- Ye code **maine yahan compile nahi kiya** (is environment mein Android SDK/internet nahi tha). Pehli build mein chhoti compile galtiyan aa sakti hain; error text bhejenge to main theek kar dunga.
- Live model ka naam Google badalta rehta hai. Khali chhodne par app aapki key ke liye khud sahi model chunti hai.
- Gemini Live ka audio session lamba chalne par reconnect hota hai; resumption handle se baat-cheet ka context bachta hai.
- Wake lock + mic 24 ghante chalne se battery kharch hoti hai.

## Abhi is version mein NAHI hai (agla batch)
Wake word "Hey Yesmin", Shizuku tier (Wi-Fi/Bluetooth ka seedha on-off, force-stop, install/uninstall), screenshot ko Gemini vision se samajhna aur OCR, macros/triggers (charger, battery, Wi-Fi par apne-aap), dusre apps ke upar floating orb button, notifications padhna/jawab dena, WhatsApp/browser ke special recipes, files/gallery/camera control, contacts add/edit, calendar events, anime avatar.

## Android ki seemayein (koi app nahi paar kar sakta)
Lock screen ka PIN/fingerprint, FLAG_SECURE wali screens (banking, kuch OTT) ka screenshot/reading, CAPTCHA, payment/OTP (jaanbujhkar band), Tier-A par Wi-Fi/Bluetooth ka seedha on-off (panel kholkar toggle dabana padta hai).

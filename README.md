<div align="center">

# GB Files

<img width="120" height="120" alt="gbfiles" src="https://github.com/user-attachments/assets/b1ccc273-7797-4b41-b204-52268c58eea3" />

**A fast, lightweight, 100% offline dual-pane file manager for Android.** 🚀

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-62B1B0?style=for-the-badge&logo=android&logoColor=white)](#)
[![Privacy](https://img.shields.io/badge/Internet%20Permission-None%20(100%25%20Offline)-2E2D2D?style=for-the-badge)](#privacy--security)
[![Ads](https://img.shields.io/badge/Ads%20%26%20Tracking-Zero-62B1B0?style=for-the-badge)](#privacy--security)
[![Release](https://img.shields.io/badge/Version-1.0-2E2D2D?style=for-the-badge)](https://github.com/YOUR_USERNAME/gb-files/releases/latest)

<br/>

<a href="https://github.com/GonoBot/GB-Files/releases/tag/v1.0">
  <img src="https://img.shields.io/badge/Download_APK-GitHub_Releases-62B1B0?style=for-the-badge&logo=android&logoColor=1B1B1B" alt="Download APK" />
</a>
&nbsp;
<a href="https://gonobot.in/">
  <img src="https://img.shields.io/badge/Download_on-Gono_Bot-2E2D2D?style=for-the-badge&logo=GonoBot&logoColor=62B1B0" alt="Download on Gono Bot" />
</a>
&nbsp;
<a href="https://gonobot.in/">
  <img src="https://img.shields.io/badge/Official_Website-gonobot.in-2E2D2D?style=for-the-badge&logo=googlechrome&logoColor=EFEFEF" alt="Official Website" />
</a>
&nbsp;
<a href="https://ko-fi.com/gonobot">
  <img src="https://img.shields.io/badge/Support_Development-Ko--fi-62B1B0?style=for-the-badge&logo=kofi&logoColor=1B1B1B" alt="Support on Ko-fi" />
</a>

</div>

---

## 📸 Screenshots

| Dual-Pane Browser & UI | Swipe-to-Select | Built-in Text & JSON Editor |
| :---: | :---: | :---: |
| <img width="230" alt="Dual-Pane Browser" src="https://github.com/user-attachments/assets/3277573c-6c79-40ea-bc06-a9a4a28c4241" /> | <img width="230" alt="Swipe Selection" src="https://github.com/user-attachments/assets/18a24966-74a3-41de-baff-012160f2cbfc" /> | <img width="230" alt="Text and JSON Editor" src="https://github.com/user-attachments/assets/7e4223e5-e736-468e-857b-f9f1f4d53602" /> |

| Categorized Bookmarks | 30-Day Recycle Bin |
| :---: | :---: |
| <img width="230" alt="Bookmarks" src="https://github.com/user-attachments/assets/f2ea039c-91d8-4b70-802e-0fe9f79c57ec" /> | <img width="230" alt="30-Day Recycle Bin" src="https://github.com/user-attachments/assets/c4d2ca58-75b5-45f2-92a6-b5b9987410db" /> |

---

## 💡 Why GB Files?

Most modern file managers are bloated with cloud upsells, hidden telemetry, and invasive permissions. **GB Files** is built from the ground up to do one job exceptionally well: manage your local storage at maximum speed with **zero internet access**.

* 🔒 **100% Offline Architecture:** Does not request `android.permission.INTERNET` or `ACCESS_NETWORK_STATE`. Your file names, folders, and metadata never leave your phone.
* 📂 **Side-by-Side Dual-Pane Workflow:** Both panels operate completely independently—navigate two folders simultaneously and copy or move files straight into the opposite panel in a single tap.
* ⚡ **Built for Large Storage:** Virtualized `RecyclerView` architecture with lazy row stat loading and thumbnail caching opens folders with thousands of photos and videos smoothly without UI freezes.

---

## ✨ Features

### 🗂️ File Management & Navigation
* **Independent Dual-Pane Split View:** Independent sorting (`Name`, `Type`, `Date & Time`, `Size`, plus reverse order), scroll memory, and selection state per panel.
* **Two-Step Haptic Swipe-to-Select:** Swipe right once on a starting item (short haptic buzz), then swipe right on the ending item (strong haptic buzz) to select the entire range in between—or tap any single item to select it and reset the range anchor.
* **Smart Conflict Resolution:** Copy or move batches with live progress and options to *Keep Both*, *Replace* (with automatic Recycle Bin backup of overwritten files), or *Skip*, plus *Apply to subsequent conflicts*.
* **30-Day Recycle Bin (Trash):** Deleted files move safely to Trash with original path metadata, real media/APK thumbnails, and one-tap restore—automatically purged after 30 days.
* **Swipe-Up Recent History Drawer:** Swipe up on the bottom toolbar anytime to jump back into your last 50 visited folders or files.
* **Categorized Bookmarks:** Save and organize quick shortcuts across *Folders*, *Files*, *Apps*, and *Images* with real thumbnails.
* **Storage Switcher & Direct Path Jump:** Tap the top path bar to switch between Internal Storage, microSD, USB OTG, or paste any path directly.
* **Recursive Local Search:** Fast background file and folder search across storage.

### 🛠️ Built-In Offline Tools & Viewers
* **📝 Free-Flow Text & Code Editor (`.txt`, `.json`, `.xml`, `.kt`, `.py`, `.md`, `.html`, `.csv`, `.log`, `.yml`, `.toml`, and more):**
  * Multi-line code paste support, live line numbers, UTF-8 character/line counter, toggleable word wrap, in-file search (`Find` / `Next` with match counter), and unsaved-changes protection.
* **📦 ZIP Archive Manager:** Browse inside `.zip` archives without extracting, extract single files or entire archives, and compress files/folders into `.zip`.
* **🖼️ Built-In Image Viewer (Including `.avif`):** Swipe smoothly between `.jpg`, `.png`, `.webp`, `.gif`, `.bmp`, and `.avif` images in your active folder's sort order, inspect resolution/dimensions, rename, delete, or share directly.
* **📄 Built-In PDF & 🎵 Audio Players:** Offline page-by-page PDF reader with smooth slider navigation, plus a lightweight audio player (`.mp3`, `.m4a`, `.wav`, `.aac`, `.ogg`, `.flac`).
* **🎬 Basic Video Player:** Built-in offline playback for standard `.mp4`, `.mkv`, `.avi`, `.mov`, and `.3gp` videos with screen lock, landscape rotation, and quick file options.

---

## ⚠️ Important Notes & Known Limitations

* **🛡️ First-Time Google Play Protect Prompt:** Because **GB Files** is an independent sideloaded release that requests *All Files Access*, Google Play Protect may display *"App scan recommended"* or *"Harmful app blocked"* until the signing certificate builds install reputation. Tap **More details → Install anyway** to install safely.
* **🎬 High-Resolution / 4K Video Playback:** **GB Files** includes a lightweight built-in video player for everyday clips, **not** a heavy hardware-accelerated media engine. High-resolution **4K** or **AV1-encoded** videos may stutter or lag during playback. For 4K/AV1 videos, tap **Open with...** and use a dedicated video player such as **VLC** or **MPV**, It decided for app to be as lightweight as possible adding `4k or Av1-encoded` Dependency cause app to jump straight to `+25 mb` more then what is already now.
* **🔐 Encrypted Archives & Password-Protected PDFs:** Standard `.zip` archives are supported natively without external native binaries; encrypted archives (`.7z`, `.rar`, password-protected `.zip`) and password-protected PDFs require a dedicated external tool.
* **📁 Android 11+ All Files Access:** On Android 11 and above, **GB Files** requires *All Files Access* (`MANAGE_EXTERNAL_STORAGE`) solely to browse, move, and manage local files across internal and external storage.

---

## 🛡️ Privacy & Security

* **🚫 Zero Network Permissions:** `INTERNET` permission is completely absent from `AndroidManifest.xml`.
* **📵 No Cloud Backup Leakage:** `android:allowBackup="false"` ensures local bookmarks, history, and paths are never uploaded via ADB or cloud backups.
* **✅ Official Build Verification:** Open **⋮ → About & Privacy** inside **GB Files** to verify that you are running an authentic, unmodified official release.

---

## ⚠️ Official Distribution & Authenticity Notice

**GB Files** is officially distributed **exclusively** through:
* **GitHub Releases:** [github.com/GonoBot/GB-Files/releases](https://github.com/GonoBot/GB-Files/releases)
* **Official Website:** [gonobot.in](https://gonobot.in/)

We do **not** upload or publish **GB Files** to third-party APK websites or external app stores. If you find this app hosted on any other website or platform without our knowledge, **do not trust or install it**—we are not responsible for modified, tampered, or unsafe builds downloaded from unofficial sources, nor can we guarantee their privacy or security.

* **Verify Your Install:** After installing, open **⋮ → About & Privacy** inside **GB Files** to confirm that your copy is a genuine, unmodified official release.

---

## 🐛 Found a Bug or Have Feedback?

**GB Files** is actively tested and improved based on real-world device feedback. If you encounter an issue or layout quirk on your phone (BUT NOT RELATED TO VIDEO BY SAYING "Hello, I am facing an issue related to video players in your app. It was not able to play 4k and 48000Hz video please fix it" SERIOUSLY I CAN'T OK ?! I Already mentioned it on 'IMPORTANT' SECTION WHY.I.CAN'T.FIX.IT!!!! Please, Please at least read 'Important and Limitations' Ahem...), please open an issue in the **[Issues](https://github.com/GonoBot/gb-files/issues)** tab and include:
1. 📱 Your **phone model** and **Android version**
2. 🔁 The **steps** that caused the issue

---

## ❤️ Support the Project

**GB Files** is free, ad-free, and built independently with no tracking or paywalls. If it saves you time every day and you would like to support ongoing updates and future platform releases, optional tips are always appreciated:

<a href="https://ko-fi.com/gonobot">
  <img src="https://img.shields.io/badge/Support_on-Ko--fi-62B1B0?style=for-the-badge&logo=kofi&logoColor=1B1B1B" alt="Support on Ko-fi" />
</a>
&nbsp;
<a href="https://www.paypal.com/paypalme/GonoBot">
  <img src="https://img.shields.io/badge/Support_via-PayPal.me-2E2D2D?style=for-the-badge&logo=paypal&logoColor=62B1B0" alt="Support via PayPal.me" />
</a>
&nbsp;
<a href="https://gonobot.in/support/">
  <img src="https://img.shields.io/badge/Support_Page-gonobot.in%2Fsupport-2E2D2D?style=for-the-badge&logo=googlechrome&logoColor=EFEFEF" alt="Official Support Page" />
</a>

---

## 🏷️ Topics & Tags
`android` `file-manager` `dual-pane` `offline` `privacy-first` `kotlin` `no-internet` `text-editor` `json-editor` `avif` `zip-extractor` `recycle-bin` `apk`

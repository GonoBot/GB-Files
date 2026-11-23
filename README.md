<div align="center">

# GB Files

<img width="120" height="120" alt="gbfiles" src="https://github.com/user-attachments/assets/b1ccc273-7797-4b41-b204-52268c58eea3" />

**A fast, lightweight, 100% offline dual-pane file manager for Android.** 🚀

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-62B1B0?style=for-the-badge&logo=android&logoColor=white)](#)
[![Privacy](https://img.shields.io/badge/Internet%20Permission-None%20(100%25%20Offline)-2E2D2D?style=for-the-badge)](#privacy--security)
[![Ads](https://img.shields.io/badge/Ads%20%26%20Tracking-Zero-62B1B0?style=for-the-badge)](#privacy--security)
[![Release](https://img.shields.io/badge/Version-1.0-2E2D2D?style=for-the-badge)](https://github.com/YOUR_USERNAME/gb-files/releases/latest)

<br/>

<a href="https://github.com/YOUR_USERNAME/gb-files/releases/latest">
  <img src="https://img.shields.io/badge/Download_APK-Latest_Release-62B1B0?style=for-the-badge&logo=android&logoColor=1B1B1B" alt="Download APK" />
</a>
&nbsp;
<a href="https://YOUR_WEBSITE_LINK_HERE">
  <img src="https://img.shields.io/badge/Official_Website-Visit-2E2D2D?style=for-the-badge&logo=googlechrome&logoColor=EFEFEF" alt="Website" />
</a>
&nbsp;
<a href="https://ko-fi.com/YOUR_SUPPORT_LINK_HERE">
  <img src="https://img.shields.io/badge/Support_Development-Ko--fi-62B1B0?style=for-the-badge&logo=kofi&logoColor=1B1B1B" alt="Support" />
</a>
&nbsp;
<a href="https://YOUR_EXTRA_LINK_HERE">
  <img src="https://img.shields.io/badge/Extra_Link-Custom_Button-2E2D2D?style=for-the-badge" alt="Extra Button" />
</a>

</div>

---

## 📸 Screenshots

| Dual-Pane Browser | Built-in Text & JSON Editor | Media & Archive Viewers |
| :---: | :---: | :---: |
| (img) | (img) | (img) |

| Swipe-Up History & Bookmarks | 30-Day Recycle Bin | Dark Minimal UI |
| :---: | :---: | :---: |
| (img) | (img) | (img) |

---

## 💡 Why GB Files?

Most modern file managers are bloated with cloud upsells, hidden telemetry, and invasive permissions. **GB Files** is built from the ground up to do one job exceptionally well: manage your local storage at maximum speed with **zero internet access**.

* 🔒 **100% Offline Architecture:** Does not request `android.permission.INTERNET` or `ACCESS_NETWORK_STATE`. Your file names, folders, and metadata never leave your phone.
* 📂 **Side-by-Side Dual-Pane Workflow:** Navigate two folders simultaneously—copy or move files from the active panel straight into the opposite panel in a single tap.
* ⚡ **Built for Large Storage:** Virtualized `RecyclerView` architecture with background directory scanning and thumbnail caching handles folders with thousands of files smoothly without UI freezes.

---

## ✨ Features

### 🗂️ File Management & Navigation
* **Dual-Pane Split View:** Independent sorting (`Name`, `Type`, `Date & Time`, `Size`, plus reverse order) and independent selection state per panel.
* **Gesture Range Selection:** Swipe right on any item to quickly select a continuous range of files.
* **Smart Conflict Resolution:** Copy or move batches with options to *Keep Both*, *Replace* (with automatic Recycle Bin backup of overwritten files), or *Skip*, plus *Apply to subsequent conflicts*.
* **30-Day Recycle Bin (Trash):** Deleted files can be moved safely to Trash with original path metadata and one-tap restore—automatically purged after 30 days.
* **Swipe-Up Recent History Drawer:** Swipe up on the bottom toolbar anytime to jump back into your last 50 visited folders or files.
* **Categorized Bookmarks:** Save and organize quick shortcuts across *Folders*, *Files*, *Apps*, and *Images*.
* **Storage Switcher & Direct Path Jump:** Tap the top path bar to switch between Internal Storage, microSD, USB OTG, or paste any path directly.
* **Recursive Local Search:** Fast background file and folder search across storage.

### 🛠️ Built-In Offline Tools & Viewers
* **📝 Text & Code Editor (`.txt`, `.json`, `.xml`, `.kt`, `.py`, `.md`, `.html`, `.csv`, `.log`, `.yml`, `.toml`, and more):**
  * Live line numbers, UTF-8 character/line counter, toggleable word wrap, in-file search (`Find` / `Next` with match counter), and unsaved-changes protection.
* **📦 ZIP Archive Manager:** Browse inside `.zip` archives without extracting, extract single files or entire archives, and compress files/folders into `.zip` with progress tracking.
* **🖼️ Built-In Image Viewer:** Swipe smoothly between images in your active folder's sort order, inspect resolution/dimensions, rename, delete, or share directly.
* **📄 Built-In PDF & 🎵 Audio Players:** Offline page-by-page PDF reader with smooth slider navigation, plus a lightweight audio player (`.mp3`, `.m4a`, `.wav`, `.aac`, `.ogg`, `.flac`).
* **🎬 Basic Video Player:** Built-in offline playback for standard `.mp4`, `.mkv`, `.avi`, `.mov`, and `.3gp` videos with screen lock, landscape rotation, and quick file options.

---

## ⚠️ Important Notes & Known Limitations

* **🎬 High-Resolution / 4K Video Playback:** **GB Files** includes a lightweight built-in video player for everyday clips, **not** a heavy hardware-accelerated media engine. High-resolution **4K** or **AV1-encoded** videos may stutter or lag during playback. For 4K/AV1 videos, tap **Open with...** and use a dedicated video player such as **VLC** or **MPV**.
* **🔐 Encrypted Archives & Password-Protected PDFs:** To keep the APK ultra-lightweight with zero third-party native binaries, standard `.zip` archives are supported natively, while encrypted archives (`.7z`, `.rar`, password-protected `.zip`) and password-protected PDFs require a dedicated external tool.
* **📁 Android 11+ All Files Access:** On Android 11 and above, **GB Files** requires *All Files Access* (`MANAGE_EXTERNAL_STORAGE`) solely to browse, move, and manage local files across internal and external storage.

---

## 🛡️ Privacy & Security

* **🚫 Zero Network Permissions:** `INTERNET` permission is completely absent from `AndroidManifest.xml`.
* **📵 No Cloud Backup Leakage:** `android:allowBackup="false"` ensures local bookmarks, history, and paths are never uploaded via ADB or cloud backups.
* **✅ Official Build Verification:** Open **⋮ → About & Privacy** inside **GB Files** to verify that you are running an authentic, unmodified official release.

---

## 🐛 Found a Bug or Have Feedback?

**GB Files** is actively tested and improved based on real-world device feedback. If you encounter an issue or layout quirk on your phone, please open an issue in the **[Issues](https://github.com/YOUR_USERNAME/gb-files/issues)** tab and include:
1. 📱 Your **phone model** and **Android version**
2. 🔁 The **steps** that caused the issue

---

## ❤️ Support the Project

**GB Files** is free, ad-free, and built independently with no tracking or paywalls. If it saves you time every day and you would like to support ongoing updates and future platform releases, optional tips are always appreciated:

<a href="https://ko-fi.com/YOUR_SUPPORT_LINK_HERE">
  <img src="https://img.shields.io/badge/Support_GB_Files-Ko--fi-62B1B0?style=for-the-badge&logo=kofi&logoColor=1B1B1B" alt="Support GB Files on Ko-fi" />
</a>
&nbsp;
<a href="https://YOUR_WEBSITE_LINK_HERE">
  <img src="https://img.shields.io/badge/Website-Visit-2E2D2D?style=for-the-badge&logo=googlechrome&logoColor=EFEFEF" alt="Website" />
</a>
&nbsp;
<a href="https://YOUR_EXTRA_LINK_HERE">
  <img src="https://img.shields.io/badge/Extra_Link-Custom_Button-2E2D2D?style=for-the-badge" alt="Extra Button" />
</a>

---

## 🏷️ Topics & Tags
`android` `file-manager` `dual-pane` `offline` `privacy-first` `kotlin` `no-internet` `text-editor` `json-editor` `zip-extractor` `recycle-bin` `apk`
